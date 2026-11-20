package com.example.jora.db;

/** Builds an OrderDao from the function environment configuration. */
public final class OrderDaoFactory {

    private OrderDaoFactory() {
    }

    public static OrderDao createFromEnv() {
        String endpoint = env("GEMINI_ENDPOINT", "");
        int port = Integer.parseInt(env("GEMINI_PORT", "8000"));
        String protocol = env("GEMINI_PROTOCOL", "http");
        String region = env("GEMINI_REGION", "cn-north-4");
        String accessKey = env("GEMINI_ACCESS_KEY", "");
        String secretKey = env("GEMINI_SECRET_KEY", "");
        String tableName = env("TABLE_NAME", "orders_table");
        int pageSize = Integer.parseInt(env("PAGE_SIZE", "10"));
        return new OrderDao(
                new HttpGeminiClient(protocol, endpoint, port, region, accessKey, secretKey),
                tableName, pageSize);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
