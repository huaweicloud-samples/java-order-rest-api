package com.example.jora.model.response;

import com.example.jora.model.Order;

import java.util.List;

/** Response payload listing a page of orders. */
public class GetOrdersResponse {
    private final String lastEvaluatedKey;
    private final List<Order> orders;

    public GetOrdersResponse(String lastEvaluatedKey, List<Order> orders) {
        this.lastEvaluatedKey = lastEvaluatedKey;
        this.orders = orders;
    }

    public String getLastEvaluatedKey() {
        return lastEvaluatedKey;
    }

    public List<Order> getOrders() {
        return orders;
    }
}
