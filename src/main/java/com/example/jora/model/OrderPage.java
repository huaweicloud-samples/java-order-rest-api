package com.example.jora.model;

import java.util.List;

/** A page of orders plus the key of the last evaluated item for pagination. */
public class OrderPage {
    private final List<Order> orders;
    private final String lastEvaluatedKey;

    public OrderPage(List<Order> orders, String lastEvaluatedKey) {
        this.orders = orders;
        this.lastEvaluatedKey = lastEvaluatedKey;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public String getLastEvaluatedKey() {
        return lastEvaluatedKey;
    }
}
