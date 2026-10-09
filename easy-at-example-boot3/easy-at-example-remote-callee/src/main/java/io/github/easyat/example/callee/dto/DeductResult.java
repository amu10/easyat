package io.github.easyat.example.callee.dto;

/** 库存服务扣减结果的简单载体，通过 Jackson 序列化返回给 caller。 */
public class DeductResult {
    private boolean success;
    private String message;

    /** 无参构造：供 Jackson 反序列化时通过 setter 填充字段。 */
    public DeductResult() {}

    /** 全参构造：业务代码直接构造结果对象时使用。 */
    public DeductResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
