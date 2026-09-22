package io.github.easyat.core;

/** Raised before execution when a DML statement cannot be compensated safely. */
public final class UnsupportedAtSqlException extends AtException {
    public UnsupportedAtSqlException(String message){super(message);}
    public UnsupportedAtSqlException(String message,Throwable cause){super(message,cause);}
}
