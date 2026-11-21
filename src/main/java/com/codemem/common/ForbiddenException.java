package com.codemem.common;

/**
 * 越权访问：调用方不属于该资源所属学习者。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
