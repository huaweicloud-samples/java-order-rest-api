package com.example.jora.model.response;

/** Error payload returned to the API gateway. */
public class ErrorMessage {
    private final String message;
    private final int statusCode;

    public ErrorMessage(String message, int statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
