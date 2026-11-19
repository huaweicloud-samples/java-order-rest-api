package com.example.jora.db;

import java.util.List;
import java.util.Map;

/** One page of scan results plus the last evaluated key, if any. */
public class ScanPage {
    private final List<Map<String, Object>> items;
    private final Map<String, Object> lastEvaluatedKey;

    public ScanPage(List<Map<String, Object>> items, Map<String, Object> lastEvaluatedKey) {
        this.items = items;
        this.lastEvaluatedKey = lastEvaluatedKey;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }

    /** Exclusive start key of the next page, or null when there are no more pages. */
    public Map<String, Object> getLastEvaluatedKey() {
        return lastEvaluatedKey;
    }
}
