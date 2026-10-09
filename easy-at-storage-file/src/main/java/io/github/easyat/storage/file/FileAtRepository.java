package io.github.easyat.storage.file;

import io.github.easyat.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * 基于本地文件系统的全局事务（AT）仓储实现，把每一笔全局事务序列化成一个 {@code <xid>.at} 文件。
 *
 * <p>这是为<b>单机 / 开发 / 演示</b>场景准备的简易存储：所有状态都在进程所在机器的磁盘上， 不具备跨进程（多实例）一致性。生产环境应当使用 Redis / JDBC 等共享存储。
 *
 * <p>并发安全由一把 {@link ReentrantReadWriteLock} 保证：读操作（{@link #find}、{@link #recoverable}、 {@link
 * #findByStatus}）走读锁；会改写状态的操作（{@link #create}、{@link #save}、{@link #transition}、 {@link
 * #claimLease}、{@link #releaseLease}、{@link #updateRecovery}）走写锁。
 * 这种粗粒度锁对单机文件存储足够，因为同一个文件系统上没有必要做更细的并发拆分。
 */
public final class FileAtRepository implements AtRepository {
    /** 存放所有 {@code *.at} 文件的目录（构造时归一化为绝对路径）。 */
    private final Path dir;

    /** 全局读写锁：保证对全局事务状态的读 / 写不会互相穿插导致状态错乱。 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 构造文件型全局事务仓储。
     *
     * @param dir 存放事务文件的目录；若不存在会被自动创建
     * @throws AtException 当目录创建失败（如权限不足、路径非法）时抛出
     */
    public FileAtRepository(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new AtException("Cannot create AT directory", e);
        }
    }

    /**
     * 创建一笔全新的全局事务记录（首次落盘）。
     *
     * <p>若同 xid 的 {@code .at} 文件已存在会抛 {@link AtException}，因为全局事务创建应当是幂等安全的， 重复创建意味着上游重试逻辑出错或 xid
     * 复用。
     *
     * @param tx 待创建的全局事务对象
     * @throws AtException 当 xid 已存在或序列化 / 落盘失败时抛出
     */
    public void create(AtTransaction tx) {
        lock.writeLock().lock();
        try {
            if (Files.exists(file(tx.getXid()))) throw new AtException("Duplicate xid");
            write(tx);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按 xid 读取一笔全局事务；文件不存在时返回 {@link Optional#empty()}。
     *
     * @param xid 全局事务标识
     * @return 命中的事务对象，或空 Optional（xid 不存在 / 文件缺失）
     * @throws AtException 当文件存在但反序列化失败时抛出
     */
    public Optional<AtTransaction> find(String xid) {
        lock.readLock().lock();
        try {
            Path p = file(xid);
            if (!Files.exists(p)) return Optional.empty();
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(p))) {
                return Optional.of((AtTransaction) in.readObject());
            } catch (Exception e) {
                throw new AtException("Cannot read " + xid, e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 覆盖保存一笔已存在的全局事务（状态变更后回写磁盘）。
     *
     * <p>与 {@link #create} 不同，{@code save} 不检查是否已存在，直接覆盖原文件。
     *
     * @param tx 待保存的全局事务对象（通常是从 {@link #find} 取出、改完状态后的同一实例）
     * @throws AtException 当序列化 / 落盘失败时抛出
     */
    public void save(AtTransaction tx) {
        lock.writeLock().lock();
        try {
            write(tx);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 扫描出<b>此刻需要被恢复调度推进</b>的全局事务集合，供后台恢复线程批量处理。
     *
     * <p>判定规则（满足其一即纳入）：
     *
     * <ul>
     *   <li>{@code ACTIVE} 且已超过 {@code deadline}（悬挂事务，发起方可能已崩溃）；
     *   <li>{@code ROLLING_BACK}（回滚尚未完成，需继续补偿）；
     *   <li>{@code COMMITTING}（本地已提交但全局未收敛，必须继续推进，否则永久残留）；
     *   <li>{@code ROLLBACK_FAILED}（回滚中途失败，需重试）。
     * </ul>
     *
     * 同时要求 {@code nextRetryAt <= now}，即到达了下一次重试时间点，避免对未到时的事务频繁打扰。
     *
     * @param now 当前时间戳（毫秒）
     * @param limit 单次最多返回多少条，防止一次恢复扫描量过大
     * @return 需要推进的事务列表（已按目录遍历顺序，最多 {@code limit} 条）
     * @throws AtException 目录扫描失败时抛出
     */
    public List<AtTransaction> recoverable(long now, int limit) {
        List<AtTransaction> out = new ArrayList<AtTransaction>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.at")) {
            for (Path p : s) {
                AtTransaction tx = find(p.getFileName().toString().replace(".at", "")).orElse(null);
                if (tx != null
                        && ((tx.getStatus() == AtStatus.ACTIVE && tx.getDeadline() <= now)
                                || tx.getStatus() == AtStatus.ROLLING_BACK
                                // COMMITTING：本地已提交但全局未收敛，必须继续推进，否则永久残留
                                || tx.getStatus() == AtStatus.COMMITTING
                                || tx.getStatus() == AtStatus.ROLLBACK_FAILED)
                        && tx.getNextRetryAt() <= now) out.add(tx);
                if (out.size() >= limit) break;
            }
        } catch (IOException e) {
            throw new AtException("Cannot scan", e);
        }
        return out;
    }

    /**
     * 在<b>乐观锁</b>前提下推进全局事务的状态机：只有当当前状态等于 {@code expected}、且版本号等于 {@code expectedVersion} 时，才应用
     * {@code next} 状态并落盘。
     *
     * <p>用「状态 + 版本号」双重校验来避免恢复线程与正常提交线程并发修改同一事务时的竞态： 谁先写成功，另一方的版本号或状态就失配而返回 {@code false}，需重新读取后再决策。
     *
     * @param xid 全局事务标识
     * @param expected 期望的当前状态
     * @param expectedVersion 期望的当前版本号
     * @param next 要切换到的目标状态
     * @return 转换成功返回 {@code true}；事务不存在或状态 / 版本校验不通过返回 {@code false}
     */
    public boolean transition(String xid, AtStatus expected, long expectedVersion, AtStatus next) {
        lock.writeLock().lock();
        try {
            AtTransaction tx = find(xid).orElse(null);
            if (tx == null) return false;
            if (tx.getStatus() != expected || tx.getVersion() != expectedVersion) return false;
            tx.applyTransition(next);
            write(tx);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 抢占（认领）一笔事务的恢复租约，确保同一时刻只有一个恢复节点在处理它。
     *
     * <p>认领成功的三种情况：事务尚无 owner、owner 正是自己（可续租）、或上一任 owner 的租约已过期 （{@code leaseUntil < now}）。认领后把
     * owner 与新的 {@code leaseUntil} 写回文件。已处于终态 （{@code isTerminal()}）的事务不再可被认领，因为终态不需要再恢复。
     *
     * @param xid 全局事务标识
     * @param owner 当前恢复节点的标识
     * @param leaseUntil 本次租约的到期时间戳
     * @param now 当前时间戳（毫秒），用于判断旧租约是否过期
     * @return 认领成功返回 {@code true}，否则（被别人持有且未过期 / 已终态 / 不存在）返回 {@code false}
     */
    public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
        lock.writeLock().lock();
        try {
            AtTransaction tx = find(xid).orElse(null);
            if (tx == null) return false;
            if (tx.getStatus().isTerminal()) return false;
            if (tx.getOwner() == null || tx.getOwner().equals(owner) || tx.getLeaseUntil() < now) {
                tx.setOwner(owner);
                tx.setLeaseUntil(leaseUntil);
                write(tx);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按状态枚举批量列出全局事务（例如找出所有 {@code ROLLING_BACK} 的事务做监控）。
     *
     * @param status 要筛选的事务状态
     * @param limit 最多返回多少条
     * @return 处于指定状态的事务列表
     * @throws AtException 目录扫描失败时抛出
     */
    @Override
    public List<AtTransaction> findByStatus(AtStatus status, int limit) {
        List<AtTransaction> out = new ArrayList<AtTransaction>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.at")) {
            for (Path p : s) {
                if (out.size() >= limit) break;
                AtTransaction tx = find(p.getFileName().toString().replace(".at", "")).orElse(null);
                if (tx != null && tx.getStatus() == status) out.add(tx);
            }
        } catch (IOException e) {
            throw new AtException("Cannot scan by status", e);
        }
        return out;
    }

    /**
     * 释放一笔事务的恢复租约（清空 owner 与租约到期时间），通常由恢复节点处理完毕后调用。
     *
     * <p>仅当 owner 为空或 owner 等于调用方时才允许释放，避免误清别人的租约。
     *
     * @param xid 全局事务标识
     * @param owner 当前调用方的标识
     */
    public void releaseLease(String xid, String owner) {
        lock.writeLock().lock();
        try {
            AtTransaction tx = find(xid).orElse(null);
            if (tx == null) return;
            if (tx.getOwner() == null || tx.getOwner().equals(owner)) {
                tx.setOwner(null);
                tx.setLeaseUntil(0);
                write(tx);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新一笔事务的恢复计数与下次重试时间，供恢复调度在每次重试失败后回写退避信息。
     *
     * @param xid 全局事务标识
     * @param retries 已重试次数（累加）
     * @param nextRetryAt 下一次允许重试的时间戳
     */
    public void updateRecovery(String xid, int retries, long nextRetryAt) {
        lock.writeLock().lock();
        try {
            AtTransaction tx = find(xid).orElse(null);
            if (tx == null) return;
            tx.setRetries(retries);
            tx.setNextRetryAt(nextRetryAt);
            write(tx);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 把事务对象原子地写到磁盘：先写同名 {@code .tmp} 临时文件，刷盘并 {@code fsync} 后， 再用「替换式」{@code Files.move} 覆盖正式文件。
     *
     * <p>这样即使进程在写的过程中崩溃，正式的 {@code .at} 文件要么还是旧内容、要么完整新内容， 不会出现半截文件导致反序列化失败。{@code
     * f.getFD().sync()} 强制把数据刷到物理磁盘， 而不只是操作系统缓存，进一步降低崩溃丢数据的概率。
     *
     * @param tx 要持久化的事务对象
     * @throws AtException 当临时文件写入 / 刷盘 / 移动失败时抛出
     */
    private void write(AtTransaction tx) {
        Path target = file(tx.getXid()), tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream f = new FileOutputStream(tmp.toFile());
                ObjectOutputStream o = new ObjectOutputStream(f)) {
            o.writeObject(tx);
            o.flush();
            // 强制刷盘：确保崩溃恢复时拿到的是完整落盘的数据，而非停在 OS 缓存里。
            f.getFD().sync();
            // 原子替换：同名 move 在多数文件系统上是原子的，避免读到写了一半的文件。
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AtException("Cannot save " + tx.getXid(), e);
        }
    }

    /**
     * 把 xid 映射成磁盘上的 {@code <xid>.at} 文件路径，并先做一层安全校验： xid 只能由字母、数字与连字符组成，防止路径穿越（如包含 {@code
     * ../}）破坏目录结构。
     *
     * @param xid 全局事务标识
     * @return 该事务对应的绝对文件路径
     * @throws IllegalArgumentException 当 xid 含有非法字符时抛出
     */
    private Path file(String xid) {
        if (!xid.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Invalid xid");
        return dir.resolve(xid + ".at");
    }
}
