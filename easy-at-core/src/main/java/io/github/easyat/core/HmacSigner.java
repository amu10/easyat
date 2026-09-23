package io.github.easyat.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/** HMAC-SHA256 signer/verifier for cross-service headers with constant-time comparison. */
public final class HmacSigner {
    private final byte[] secret;
    public HmacSigner(String secret){this.secret=secret==null?null:secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    public boolean isConfigured(){return secret!=null&&secret.length>0;}
    public String sign(String xid,long deadline,String source){
        if(!isConfigured())throw new IllegalStateException("HMAC secret is not configured");
        return hex(hmac(payload(xid,deadline,source)));
    }
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
    private static String payload(String xid,long deadline,String source){return xid+"\n"+deadline+"\n"+source;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder(b.length*2);for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private static byte[] hexDecode(String s){if(s.length()%2!=0)throw new IllegalArgumentException("bad hex");byte[] o=new byte[s.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return o;}
}
