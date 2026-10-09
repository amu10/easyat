package io.github.easyat.storage.file;

import io.github.easyat.core.AtException;
import io.github.easyat.core.GlobalLockConflictException;
import io.github.easyat.core.GlobalLockManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 基于文件系统（一个 lock 文件对应一把全局锁）的全局锁实现，<b>仅适用于单机部署</b>。
 *
 * <p>AT 模式的核心之一：在本地事务提交前，必须先对「资源 + 表 + 主键」这把全局锁加锁成功，
 * 才能提交；否则说明有别的全局事务正在改同一行，必须冲突重试。多机部署必须换成
 * Redis / JDBC 等能被所有节点共享的锁实现，否则各实例各自的文件锁互不感知，等于没加锁。
 *
 * <p>锁的粒度是「一行」——文件名编码了 {@code resourceId_tableName_primaryKey}，所以两个事务
 * 改同一行会争用同一个 lock 文件；改不同行则互不影响。
 */
public final class FileGlobalLockManager implements GlobalLockManager {
    /** 存放所有 {@code *.lock} 文件的目录（构造时归一化为绝对路径）。 */
    private final Path directory;

    /** 获取锁时的默认等待时长（毫秒）；0 表示不等待、抢不到立即失败。 */
    private final long waitMillis;

    /**
     * 构造全局锁管理器（默认不等待，抢不到即冲突）。
     *
     * @param directory 存放锁文件的目录
     */
    public FileGlobalLockManager(Path directory) {
        this(directory, 0L);
    }

    /**
     * 构造全局锁管理器，并指定获取锁时的等待时长。
     *
     * @param directory 存放锁文件的目录
     * @param waitMillis 抢不到锁时的最大等待毫秒数；0 表示不等待
     * @throws AtException 当锁目录创建失败时抛出
     */
    public FileGlobalLockManager(Path directory, long waitMillis) {
        this.directory = directory.toAbsolutePath().normalize();
        this.waitMillis = waitMillis;
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new AtException("Cannot create lock directory", e);
        }
    }

    /**
     * 获取锁（使用构造时设定的默认等待时长）。
     *
     * @param resourceId 资源标识
     * @param tableName 表名
     * @param primaryKey 主键值（锁到「行」粒度）
     * @param xid 当前持有锁的全局事务标识
     */
    @Override
    public void acquire(String resourceId, String tableName, String primaryKey, String xid) {
        acquire(resourceId, tableName, primaryKey, xid, waitMillis);
    }

    /**
     * 获取「资源 + 表 + 主键」这一行的全局锁。
     *
     * <p>实现方式：以 {@code CREATE_NEW} 方式创建 lock 文件，文件内容就是持有者 xid。
     * 若文件已存在则抛出 {@link FileAlreadyExistsException}，此时再读文件内容：
     * <ul>
     *   <li>若内容就是本事务自己的 xid，说明是可重入，直接返回成功；</li>
     *   <li>若是别的事务，则按 {@code waitMillis} 退避重试，直到超时抛出 {@link GlobalLockConflictException}。</li>
     * </ul>
     * 用「建文件原子性」替代了真正的互斥原语，单机下足够；{@code CREATE_NEW} 在文件系统上是原子的，
     * 天然避免了两个进程同时抢到锁的竞态。
     *
     * @param resourceId 资源标识
     * @param tableName 表名
     * @param primaryKey 主键值
     * @param xid 当前持有锁的事务标识
     * @param waitMillis 本次获取最多等待的毫秒数；<=0 表示不等待
     * @throws GlobalLockConflictException 当等待超时仍被别的事务占有时抛出
     * @throws AtException 当被中断或 IO 异常时抛出
     */
    @Override
    public void acquire(
            String resourceId, String tableName, String primaryKey, String xid, long waitMillis) {
        Path path = file(resourceId, tableName, primaryKey);
        long deadline =
                waitMillis <= 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            try {
                Files.write(
                        path,
                        Collections.singletonList(xid),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                return;
            } catch (FileAlreadyExistsException exists) {
                try {
                    List<String> owner = Files.readAllLines(path, StandardCharsets.UTF_8);
                    if (owner.size() == 1 && xid.equals(owner.get(0))) return;
                } catch (IOException ignored) {
                }
                if (waitMillis > 0 && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AtException("Interrupted acquiring lock", ie);
                    }
                    continue;
                }
                throw new GlobalLockConflictException(
                        "Global lock conflict: " + resourceId + "/" + tableName + "/" + primaryKey);
            } catch (IOException e) {
                throw new AtException("Cannot acquire global lock", e);
            }
        }
    }

    /**
     * 释放某个事务持有的<b>所有</b>全局锁：扫描目录下所有 lock 文件，凡是内容为本 xid 的一律删除。
     *
     * <p>全局事务提交 / 回滚完成后必须调用，否则这些锁文件会一直占着，导致后续改同一行的事务永远冲突。
     *
     * @param xid 要释放锁的事务标识
     * @throws AtException 当目录扫描失败时抛出
     */
    @Override
    public void releaseByXid(String xid) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.lock")) {
            for (Path path : stream) {
                try {
                    List<String> owner = Files.readAllLines(path, StandardCharsets.UTF_8);
                    if (owner.size() == 1 && xid.equals(owner.get(0))) Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new AtException("Cannot release global locks", e);
        }
    }

    /**
     * 把「资源 + 表 + 主键」编码成 lock 文件路径。三段都先经过 {@link #safe} 转义，
     * 保证文件名里不会出现会破坏路径结构的字符。
     *
     * @param resource 资源标识
     * @param table 表名
     * @param key 主键值
     * @return 对应的 {@code *.lock} 绝对文件路径
     */
    private Path file(String resource, String table, String key) {
        return directory.resolve(safe(resource) + "_" + safe(table) + "_" + safe(key) + ".lock");
    }

    /**
     * 文件名安全化：把除字母、数字、{@code . _ -} 之外的字符替换为下划线，
     * 防止表名 / 主键值里含有 {@code /} 或 {@code ..} 造成路径穿越。
     *
     * @param value 原始字符串
     * @return 仅含安全字符的字符串
     */
    private static String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
