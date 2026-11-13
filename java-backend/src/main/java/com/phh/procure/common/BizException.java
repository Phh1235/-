package com.phh.procure.common;

/**
 * 业务异常：携带错误码，由 {@link GlobalExceptionHandler} 统一转换为响应。
 */
public class BizException extends RuntimeException {

    private final ErrorCode code;

    public BizException(ErrorCode code) {
        super(code.getDefaultMessage());
        this.code = code;
    }

    public BizException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }
}
