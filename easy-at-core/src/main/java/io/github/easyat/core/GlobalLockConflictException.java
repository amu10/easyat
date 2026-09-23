package io.github.easyat.core;

/** Thrown when a global lock cannot be acquired within the configured wait timeout. */
public final class GlobalLockConflictException extends AtException {
    public GlobalLockConflictException(String message) {
        super(message);
    }

    public GlobalLockConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
