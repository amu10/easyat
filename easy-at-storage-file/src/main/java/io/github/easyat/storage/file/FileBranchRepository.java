package io.github.easyat.storage.file;

import io.github.easyat.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * 基于本地文件系统的分支事务（branch）注册表，把每个分支序列化成一个 {@code <branchId>.branch} 文件。
 *
 * <p>与 {@link FileAtRepository} 同理，这是为<b>单机 / 开发 / 演示</b>场景准备的简易实现，
 * 不具备跨实例一致性；生产环境应改用 Redis / JDBC 等共享存储。一个全局事务下通常有多个分支
 * （每个被卷入的资源 / 库一个），分支记录用于回滚时定位「要补偿哪些 undo」。
 *
 * <p>并发安全同样由一把 {@link ReentrantReadWriteLock} 保证：读操作走读锁，写操作（{@code register}、
 * {@code transition}、{@code updateRecovery}、{@code save}）走写锁。
 */
public final class FileBranchRepository implements BranchRepository {
    /** 存放所有 {@code *.branch} 文件的目录（构造时归一化为绝对路径）。 */
    private final Path dir;

    /** 分支注册表的读写锁：读多写少，用一把粗粒度锁即可满足文件存储的需求。 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 构造文件型分支注册表。
     *
     * @param dir 存放分支文件的目录；若不存在会被自动创建
     * @throws AtException 当目录创建失败时抛出
     */
    public FileBranchRepository(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new AtException("Cannot create branch directory", e);
        }
    }

    /**
     * 注册一个分支。若同一 {@code (xid, resourceId)} 的分支已存在则视为幂等、直接返回，
     * 与 JDBC 实现中 {@code UNIQUE(xid, resourceId)} 的唯一约束语义保持一致。
     *
     * @param b 待注册的分支对象
     */
    @Override
    public void register(AtBranch b) {
        lock.writeLock().lock();
        try {
            // 与 JDBC 的 UNIQUE(xid, resource_id) 同语义：同一资源在同一事务下只保留第一条分支，
            // 重复注册是幂等操作（写锁保证这里没有并发窗口）。
            for (AtBranch existing : all())
                if (existing.getXid().equals(b.getXid())
                        && existing.getResourceId().equals(b.getResourceId())) return;
            write(b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按 {@code (xid, resourceId)} 精确查找分支（全局事务 + 具体资源确定唯一分支）。
     *
     * @param xid 全局事务标识
     * @param resourceId 资源标识（通常对应一个数据库连接 / 库）
     * @return 命中的分支，或空 Optional
     */
    @Override
    public Optional<AtBranch> findByXidResource(String xid, String resourceId) {
        if (xid == null || resourceId == null) return Optional.empty();
        lock.readLock().lock();
        try {
            for (AtBranch b : all())
                if (b.getXid().equals(xid) && b.getResourceId().equals(resourceId))
                    return Optional.of(b);
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按分支 ID 查找分支。
     *
     * @param branchId 分支标识
     * @return 命中的分支，或空 Optional（不存在 / 读取失败）
     */
    @Override
    public Optional<AtBranch> find(String branchId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(read(branchId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 在乐观锁前提下推进分支状态：仅当当前状态等于 {@code expected} 才切换到 {@code next}。
     *
     * <p>用于把分支从 {@code REGISTERED} 推进到 {@code COMMITTED} 或 {@code ROLLED_BACK} 等，
     * 状态 / 写入不匹配则返回 {@code false}，由调用方重新决策。
     *
     * @param branchId 分支标识
     * @param expected 期望的当前状态
     * @param next 目标状态
     * @return 转换成功返回 {@code true}，否则返回 {@code false}
     */
    @Override
    public boolean transition(String branchId, BranchStatus expected, BranchStatus next) {
        lock.writeLock().lock();
        try {
            AtBranch b = read(branchId);
            if (b == null || b.getStatus() != expected) return false;
            b.setStatus(next);
            write(b);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 列出某笔全局事务下的所有分支，并按分支注册顺序（{@code sequence}）排序。
     *
     * <p>回滚分支时需要按注册的逆序（或固定序）补偿，这里先按 {@code sequence} 正序返回，
     * 由上层决定如何遍历。
     *
     * @param xid 全局事务标识
     * @return 该事务下所有分支（已按 sequence 升序）
     */
    @Override
    public List<AtBranch> byXid(String xid) {
        lock.readLock().lock();
        try {
            List<AtBranch> out = new ArrayList<AtBranch>();
            for (AtBranch b : all()) if (b.getXid().equals(xid)) out.add(b);
            out.sort(
                    new Comparator<AtBranch>() {
                        public int compare(AtBranch a, AtBranch b) {
                            return Integer.compare(a.getSequence(), b.getSequence());
                        }
                    });
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 扫描出<b>此刻需要继续回滚</b>的分支：处于 {@code ROLLING_BACK} 或 {@code ROLLBACK_FAILED}
     * 状态，且已达到下次重试时间（{@code nextRetryAt <= now}）。
     *
     * @param now 当前时间戳（毫秒）
     * @param limit 单次最多返回多少条
     * @return 待处理的分支列表
     */
    @Override
    public List<AtBranch> pendingActions(long now, int limit) {
        lock.readLock().lock();
        try {
            List<AtBranch> out = new ArrayList<AtBranch>();
            for (AtBranch b : all())
                if ((b.getStatus() == BranchStatus.ROLLING_BACK
                                || b.getStatus() == BranchStatus.ROLLBACK_FAILED)
                        && b.getNextRetryAt() <= now) {
                    out.add(b);
                    if (out.size() >= limit) break;
                }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 更新分支的重试计数与下次重试时间，供恢复调度在回滚失败后退避重试。
     *
     * @param branchId 分支标识
     * @param retries 已重试次数
     * @param nextRetryAt 下一次允许重试的时间戳
     */
    @Override
    public void updateRecovery(String branchId, int retries, long nextRetryAt) {
        lock.writeLock().lock();
        try {
            AtBranch b = read(branchId);
            if (b == null) return;
            b.setRetries(retries);
            b.setNextRetryAt(nextRetryAt);
            write(b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 覆盖保存一个分支（状态变更后回写磁盘）。
     *
     * @param b 待保存的分支对象
     */
    @Override
    public void save(AtBranch b) {
        lock.writeLock().lock();
        try {
            write(b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 遍历目录下的所有 {@code *.branch} 文件，逐个反序列化成分支对象并汇总。
     *
     * @return 目录中所有分支对象的列表（损坏不可读的文件会被跳过）
     * @throws AtException 目录扫描本身失败时抛出
     */
    private List<AtBranch> all() {
        List<AtBranch> out = new ArrayList<AtBranch>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.branch")) {
            for (Path p : s) {
                AtBranch b = read(p.getFileName().toString().replace(".branch", ""));
                if (b != null) out.add(b);
            }
        } catch (IOException e) {
            throw new AtException("Cannot scan branches", e);
        }
        return out;
    }

    /**
     * 按 branchId 读取并反序列化一个分支对象。
     *
     * <p>文件不存在 / 不可读（{@link IOException}）时返回 {@code null}——这类情况视为「分支不存在」；
     * 但如果是反序列化等逻辑错误则抛 {@link AtException}，避免把损坏数据悄悄吞掉。
     *
     * @param branchId 分支标识
     * @return 分支对象，文件缺失时返回 {@code null}
     * @throws AtException 当文件存在但反序列化失败时抛出
     */
    private AtBranch read(String branchId) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file(branchId)))) {
            return (AtBranch) in.readObject();
        } catch (IOException e) {
            return null;
        } catch (Exception e) {
            throw new AtException("Cannot read branch " + branchId, e);
        }
    }

    /**
     * 把分支对象序列化写入对应 {@code .branch} 文件，写完立即 {@code fsync} 保证落盘。
     *
     * <p>注意这里<b>没有</b>用 {@code .tmp} + 原子 {@code move} 的方式（与 {@link FileAtRepository#write}
     * 不同）——分支文件通常由同一把写锁串行写入，且分支恢复对「半截文件」的容忍度更高；
     * 但同样做了 {@code fsync}，避免崩溃丢数据。
     *
     * @param b 待写入的分支对象
     * @throws AtException 当写入 / 刷盘失败时抛出
     */
    private void write(AtBranch b) {
        try {
            FileOutputStream f = new FileOutputStream(file(b.getBranchId()).toFile());
            ObjectOutputStream o = new ObjectOutputStream(f);
            o.writeObject(b);
            o.flush();
            f.getFD().sync();
        } catch (IOException e) {
            throw new AtException("Cannot write branch " + b.getBranchId(), e);
        }
    }

    /**
     * 把 branchId 映射成磁盘上的 {@code <branchId>.branch} 文件路径，并做安全校验：
     * branchId 只能由字母、数字与连字符组成，防止路径穿越。
     *
     * @param branchId 分支标识
     * @return 该分支对应的绝对文件路径
     * @throws IllegalArgumentException 当 branchId 含有非法字符时抛出
     */
    private Path file(String branchId) {
        if (!branchId.matches("[A-Za-z0-9-]+"))
            throw new IllegalArgumentException("Invalid branchId");
        return dir.resolve(branchId + ".branch");
    }
}
