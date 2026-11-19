package com.example.jora.handler;

import com.example.jora.model.response.ErrorMessage;
import com.google.gson.Gson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds APIG pass-through responses: {statusCode, headers, isBase64Encoded, body}. */
public final class Responses {

    private static final Map<String, String> JSON_HEADERS =
            Collections.singletonMap("Content-Type", "application/json");

    private Responses() {
    }

    public static Map<String, Object> json(int statusCode, String bodyJson) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", JSON_HEADERS);
        response.put("isBase64Encoded", false);
        response.put("body", bodyJson);
        return response;
    }

    public static Map<String, Object> error(Gson gson, int statusCode, String message) {
        return json(statusCode, gson.toJson(new ErrorMessage(message, statusCode)));
    }
}
