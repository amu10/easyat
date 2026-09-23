package io.github.easyat.storage.file;

import io.github.easyat.core.AtException;
import io.github.easyat.core.GlobalLockConflictException;
import io.github.easyat.core.GlobalLockManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Single-host lock implementation. Use Redis/JDBC locks for a multi-host deployment. */
public final class FileGlobalLockManager implements GlobalLockManager {
    private final Path directory;
    private final long waitMillis;

    public FileGlobalLockManager(Path directory) {
        this(directory, 0L);
    }

    public FileGlobalLockManager(Path directory, long waitMillis) {
        this.directory = directory.toAbsolutePath().normalize();
        this.waitMillis = waitMillis;
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new AtException("Cannot create lock directory", e);
        }
    }

    @Override
    public void acquire(String resourceId, String tableName, String primaryKey, String xid) {
        acquire(resourceId, tableName, primaryKey, xid, waitMillis);
    }

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

    private Path file(String resource, String table, String key) {
        return directory.resolve(safe(resource) + "_" + safe(table) + "_" + safe(key) + ".lock");
    }

    private static String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
