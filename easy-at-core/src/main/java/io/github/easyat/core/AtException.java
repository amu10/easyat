package io.github.easyat.core;

public class AtException extends RuntimeException {
    public AtException(String m) {
        super(m);
    }

    public AtException(String m, Throwable e) {
        super(m, e);
    }
}
