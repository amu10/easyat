package io.github.easyat.core;

/** Canonical transport header names used by both clients and servers. */
public final class AtTransportHeaders {
    public static final String XID="X-EasyAt-Xid";
    public static final String DEADLINE="X-EasyAt-Deadline";
    public static final String SOURCE="X-EasyAt-Source";
    public static final String SIGNATURE="X-EasyAt-Signature";
    private AtTransportHeaders(){}
}
