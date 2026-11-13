package com.phh.procure.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。code 段位：4xxxx 客户端、5xxxx 服务端，便于日志与前端分类。
 */
public enum ErrorCode {

    BAD_REQUEST(40000, HttpStatus.BAD_REQUEST, "参数校验失败"),
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "没有权限执行该操作"),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(40900, HttpStatus.CONFLICT, "状态冲突"),
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    INTERNAL(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),
    AGENT_UNAVAILABLE(50200, HttpStatus.BAD_GATEWAY, "Agent 执行服务不可用"),
    AGENT_CALLBACK_INVALID(40101, HttpStatus.UNAUTHORIZED, "回调签名校验失败");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
