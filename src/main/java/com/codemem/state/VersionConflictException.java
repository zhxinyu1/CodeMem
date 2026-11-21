package com.codemem.state;

/**
 * 状态版本冲突：调用方持有的 baseVersion 已过期。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
