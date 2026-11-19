package com.example.jora.handler;

/** Small shared helpers for the handler layer. */
public final class Strings {

    private Strings() {
    }

    public static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
