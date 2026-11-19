package com.example.jora.tests;

import com.example.jora.db.GeminiClient;
import com.example.jora.db.ScanPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory fake of the DynamoDB-compatible interface for unit tests. */
public class FakeGeminiClient implements GeminiClient {

    private final Map<String, LinkedHashMap<String, Map<String, Object>>> tables = new LinkedHashMap<>();

    private LinkedHashMap<String, Map<String, Object>> table(String name) {
        return tables.computeIfAbsent(name, k -> new LinkedHashMap<>());
    }

    private static String keyOf(Map<String, Object> key) {
        return (String) ((Map<?, ?>) key.get("orderId")).get("S");
    }

    @Override
    public void putItem(String tableName, Map<String, Object> item) {
        table(tableName).put(keyOf(item), new LinkedHashMap<>(item));
    }

    @Override
    public Map<String, Object> getItem(String tableName, Map<String, Object> key) {
        Map<String, Object> item = table(tableName).get(keyOf(key));
        return item == null ? null : new LinkedHashMap<>(item);
    }

    @Override
    public void deleteItem(String tableName, Map<String, Object> key) {
        table(tableName).remove(keyOf(key));
    }

    @Override
    public ScanPage scan(String tableName, Integer limit, Map<String, Object> exclusiveStartKey) {
        LinkedHashMap<String, Map<String, Object>> table = table(tableName);
        List<String> keys = new ArrayList<>(table.keySet());
        Collections.sort(keys);
        int start = 0;
        if (exclusiveStartKey != null) {
            int index = keys.indexOf(keyOf(exclusiveStartKey));
            start = index < 0 ? 0 : index + 1;
        }
        int remaining = Math.max(0, keys.size() - start);
        int count = limit == null ? remaining : Math.min(limit, remaining);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = start; i < start + count; i++) {
            items.add(new LinkedHashMap<>(table.get(keys.get(i))));
        }
        Map<String, Object> lastEvaluatedKey = null;
        if (count > 0 && start + count < keys.size()) {
            lastEvaluatedKey = new LinkedHashMap<>();
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("S", keys.get(start + count - 1));
            lastEvaluatedKey.put("orderId", attribute);
        }
        return new ScanPage(items, lastEvaluatedKey);
    }
}
