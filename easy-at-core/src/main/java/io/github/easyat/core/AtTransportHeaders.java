package io.github.easyat.core;

/**
 * 跨服务传播的传输头常量（DESIGN.md §9.1）。客户端注入、服务端校验都用这里定义的名字，
 * 避免手写字符串不一致。四个头一起构成一次可信的 XID 传播：
 * <ul>
 *   <li>{@code Xid}：全局事务 ID；</li>
 *   <li>{@code Deadline}：epoch 毫秒，超时即拒，防重放；</li>
 *   <li>{@code Source}：调用方应用名，用于服务间识别；</li>
 *   <li>{@code Signature}：对 xid+deadline+source 的 HMAC 签名。</li>
 * </ul>
 */
public final class AtTransportHeaders {
    public static final String XID="X-EasyAt-Xid";
    public static final String DEADLINE="X-EasyAt-Deadline";
    public static final String SOURCE="X-EasyAt-Source";
    public static final String SIGNATURE="X-EasyAt-Signature";
    private AtTransportHeaders(){}
}
