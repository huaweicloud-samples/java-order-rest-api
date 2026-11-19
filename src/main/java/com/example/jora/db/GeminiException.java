package com.example.jora.db;

/** Structured error returned by the DynamoDB-compatible endpoint. */
public class GeminiException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public GeminiException(String operation, int httpStatus, String code, String message) {
        super("GeminiDB " + operation + " failed with HTTP " + httpStatus + ": " + code
                + (message.isEmpty() ? "" : " (" + message + ")"));
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
