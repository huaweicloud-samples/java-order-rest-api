package com.example.jora.exception;

public class UnableToUpdateException extends IllegalStateException {
    public UnableToUpdateException(String message) {
        super(message);
    }
}
