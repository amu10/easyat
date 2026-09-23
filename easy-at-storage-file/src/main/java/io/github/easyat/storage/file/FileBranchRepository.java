package io.github.easyat.storage.file;

import io.github.easyat.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

/** File-backed branch registry, intended for single-instance / dev use. */
public final class FileBranchRepository implements BranchRepository {
    private final Path dir;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public FileBranchRepository(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new AtException("Cannot create branch directory", e);
        }
    }

    @Override
    public void register(AtBranch b) {
        lock.writeLock().lock();
        try {
            write(b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AtBranch> find(String branchId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(read(branchId));
        } finally {
            lock.readLock().unlock();
        }
    }

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

    @Override
    public void save(AtBranch b) {
        lock.writeLock().lock();
        try {
            write(b);
        } finally {
            lock.writeLock().unlock();
        }
    }

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

    private AtBranch read(String branchId) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file(branchId)))) {
            return (AtBranch) in.readObject();
        } catch (IOException e) {
            return null;
        } catch (Exception e) {
            throw new AtException("Cannot read branch " + branchId, e);
        }
    }

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

    private Path file(String branchId) {
        if (!branchId.matches("[A-Za-z0-9-]+"))
            throw new IllegalArgumentException("Invalid branchId");
        return dir.resolve(branchId + ".branch");
    }
}
