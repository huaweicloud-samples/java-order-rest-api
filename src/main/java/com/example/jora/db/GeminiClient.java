package com.example.jora.db;

import java.util.List;
import java.util.Map;

/**
 * Minimal client for the DynamoDB-compatible interface of GeminiDB.
 * Only the operations the order service needs are declared; items and keys
 * use the DynamoDB attribute wire format, e.g. {"orderId": {"S": "..."}}.
 */
public interface GeminiClient {

    /** Writes a full item (create or replace). */
    void putItem(String tableName, Map<String, Object> item);

    /** Reads an item by key; returns null when the item does not exist. */
    Map<String, Object> getItem(String tableName, Map<String, Object> key);

    /** Deletes an item by key. */
    void deleteItem(String tableName, Map<String, Object> key);

    /** Scans at most {@code limit} items, optionally after {@code exclusiveStartKey}. */
    ScanPage scan(String tableName, Integer limit, Map<String, Object> exclusiveStartKey);
}
