package com.codemem.common;

public record ApiError(String code, String message, String traceId) {
}