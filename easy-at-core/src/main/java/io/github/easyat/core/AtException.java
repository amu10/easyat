package io.github.easyat.core;

/** easyAt 的统一运行时异常：状态迁移非法、SQL 不被 AT 支持、加解密失败等，都归到这一类。 */
public class AtException extends RuntimeException {
    public AtException(String m) {
        super(m);
    }

    public AtException(String m, Throwable e) {
        super(m, e);
    }
}
