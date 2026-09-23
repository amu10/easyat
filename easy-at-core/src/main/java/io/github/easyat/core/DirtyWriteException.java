package io.github.easyat.core;

/** Raised by the undo executor when the current row no longer matches the recorded after-image. */
public final class DirtyWriteException extends AtException {
    public DirtyWriteException(String message) {
        super(message);
    }

    public DirtyWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
