package com.example.jora.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GeminiDB DynamoDB-compatible endpoint client using the four-segment request signing scheme
 * and only the JDK plus gson. Wire format is the DynamoDB JSON protocol
 * (x-amz-target: DynamoDB_20120810.Operation).
 */
public class HttpGeminiClient implements GeminiClient {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;

    private final String endpointUrl;
    private final String hostHeader;
    private final String canonicalUri;
    private final String region;
    private final String accessKey;
    private final String secretKey;

    public HttpGeminiClient(String protocol, String endpoint, int port,
                            String region, String accessKey, String secretKey) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("GeminiDB endpoint is required");
        }
        String raw = endpoint.contains("://") ? endpoint : protocol + "://" + endpoint;
        try {
            URL url = new URL(raw);
            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("GeminiDB endpoint must contain a host");
            }
            int effectivePort = url.getPort() > 0 ? url.getPort() : port;
            String path = url.getPath() == null || url.getPath().isEmpty() ? "" : url.getPath().replaceAll("/+$", "");
            this.endpointUrl = url.getProtocol() + "://" + host + ":" + effectivePort + path;
            this.hostHeader = host + ":" + effectivePort;
            this.canonicalUri = path.isEmpty() ? "/" : path;
        } catch (IOException e) {
            throw new IllegalArgumentException("GeminiDB endpoint is not a valid URL: " + raw, e);
        }
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public void putItem(String tableName, Map<String, Object> item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TableName", tableName);
        payload.put("Item", item);
        call("PutItem", payload);
    }

    @Override
    public Map<String, Object> getItem(String tableName, Map<String, Object> key) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TableName", tableName);
        payload.put("Key", key);
        Map<String, Object> response = call("GetItem", payload);
        Object item = response.get("Item");
        if (!(item instanceof Map) || ((Map<?, ?>) item).isEmpty()) {
            return null;
        }
        return asMap(item);
    }

    @Override
    public void deleteItem(String tableName, Map<String, Object> key) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TableName", tableName);
        payload.put("Key", key);
        call("DeleteItem", payload);
    }

    @Override
    public ScanPage scan(String tableName, Integer limit, Map<String, Object> exclusiveStartKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TableName", tableName);
        if (limit != null) {
            payload.put("Limit", limit);
        }
        if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
            payload.put("ExclusiveStartKey", exclusiveStartKey);
        }
        Map<String, Object> response = call("Scan", payload);
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawItems = response.get("Items");
        if (rawItems instanceof List) {
            for (Object rawItem : (List<?>) rawItems) {
                items.add(asMap(rawItem));
            }
        }
        Object lastKey = response.get("LastEvaluatedKey");
        Map<String, Object> lastEvaluatedKey =
                lastKey instanceof Map && !((Map<?, ?>) lastKey).isEmpty() ? asMap(lastKey) : null;
        return new ScanPage(items, lastEvaluatedKey);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> call(String operation, Map<String, Object> payload) {
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw new IllegalStateException("GeminiDB access key and secret key are required");
        }
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        String date = DATE.format(now);
        String amzDate = AMZ_DATE.format(now);
        String payloadHash = sha256Hex(body);

        Map<String, String> headers = new TreeMap<>();
        headers.put("content-type", "application/x-amz-json-1.0");
        headers.put("host", hostHeader);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-target", "DynamoDB_20120810." + operation);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        String canonicalRequest = String.join("\n",
                "POST", canonicalUri, "", canonicalHeaders.toString(), signedHeaders.toString(), payloadHash);
        String scope = date + "/" + region + "/dynamodb/aws4_request";
        String stringToSign = String.join("\n",
                "AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = signingKey(secretKey, date, region);
        String signature = hex(hmacSha256(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpointUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-amz-json-1.0");
            connection.setRequestProperty("X-Amz-Date", amzDate);
            connection.setRequestProperty("X-Amz-Content-Sha256", payloadHash);
            connection.setRequestProperty("X-Amz-Target", "DynamoDB_20120810." + operation);
            connection.setRequestProperty("Authorization", authorization);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            String raw = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            Map<String, Object> parsed = raw.isEmpty()
                    ? new LinkedHashMap<>()
                    : GSON.fromJson(raw, new TypeToken<Map<String, Object>>() { }.getType());
            if (parsed == null) {
                throw new IllegalStateException("GeminiDB returned a non-object response");
            }
            if (status >= 400) {
                String code = String.valueOf(parsed.getOrDefault("__type",
                        parsed.getOrDefault("code", "HTTPError")));
                code = code.substring(code.lastIndexOf('#') + 1);
                String message = String.valueOf(parsed.getOrDefault("message", ""));
                throw new GeminiException(operation, status, code, message);
            }
            return parsed;
        } catch (GeminiException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("GeminiDB " + operation + " network failure: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] signingKey(String secretKey, String date, String region) {
        byte[] key = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        key = hmacSha256(key, date);
        key = hmacSha256(key, region);
        key = hmacSha256(key, "dynamodb");
        return hmacSha256(key, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
