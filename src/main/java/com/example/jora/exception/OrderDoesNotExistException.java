package com.example.jora.exception;

public class OrderDoesNotExistException extends IllegalArgumentException {
    public OrderDoesNotExistException(String message) {
        super(message);
    }
}
