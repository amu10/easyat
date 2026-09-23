package io.github.easyat.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/** HMAC-SHA256 signer/verifier for cross-service headers with constant-time comparison. */
public final class HmacSigner {
    private final byte[] secret;
    public HmacSigner(String secret){this.secret=secret==null?null:secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    public boolean isConfigured(){return secret!=null&&secret.length>0;}
    /** 对 {xid, deadline, source} 三元组做 HMAC-SHA256 签名，返回 hex 字符串。 */
    public String sign(String xid,long deadline,String source){
        if(!isConfigured())throw new IllegalStateException("HMAC secret is not configured");
        return hex(hmac(payload(xid,deadline,source)));
    }
    /**
     * 校验签名：① 拒绝超时（deadline 早于当前时间，防重放）；② 用 {@link MessageDigest#isEqual}
     * 做恒定时间比较，防时序侧信道。注意：本方法<b>不校验 source 白名单</b>，仅验证「持有共享
     * 密钥方发来的合法签名」，服务间认证需在上层叠加 source 白名单（见生产安全缺口清单）。
     */
    public boolean verify(String xid,long deadline,String source,String signature){
        if(!isConfigured()||signature==null)return false;
        if(System.currentTimeMillis()>deadline)return false;
        byte[] expected=hmac(payload(xid,deadline,source));
        byte[] actual;
        try{actual=hexDecode(signature);}catch(IllegalArgumentException e){return false;}
        return MessageDigest.isEqual(expected,actual);
    }
    private byte[] hmac(String data){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception e){throw new AtException("Cannot compute HMAC",e);}
    }
    /** 签名原文固定为三行拼接，顺序与 header 保持一致，任何字段变动都会导致签名失效。 */
    private static String payload(String xid,long deadline,String source){return xid+"\n"+deadline+"\n"+source;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder(b.length*2);for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private static byte[] hexDecode(String s){if(s.length()%2!=0)throw new IllegalArgumentException("bad hex");byte[] o=new byte[s.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return o;}
}
