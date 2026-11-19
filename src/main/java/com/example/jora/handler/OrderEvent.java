package com.example.jora.handler;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

/**
 * Read-only view over the APIG pass-through event. Relevant fields:
 * httpMethod, path, pathParameters, queryStringParameters, body and
 * isBase64Encoded (APIG base64-encodes request bodies by default).
 */
public final class OrderEvent {

    private final Map<String, Object> event;

    public OrderEvent(Map<String, Object> event) {
        this.event = event == null ? Collections.emptyMap() : event;
    }

    public String getHttpMethod() {
        return stringValue(event.get("httpMethod"));
    }

    public String getPath() {
        return stringValue(event.get("path"));
    }

    public String pathParameter(String name) {
        return nestedValue("pathParameters", name);
    }

    public String queryParameter(String name) {
        return nestedValue("queryStringParameters", name);
    }

    /** Returns the decoded request body, or null when the request has none. */
    public String body() {
        Object raw = event.get("body");
        if (!(raw instanceof String)) {
            return null;
        }
        String text = (String) raw;
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        }
        return text;
    }

    private String nestedValue(String mapName, String key) {
        Object nested = event.get(mapName);
        if (!(nested instanceof Map)) {
            return null;
        }
        return stringValue(((Map<?, ?>) nested).get(key));
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
