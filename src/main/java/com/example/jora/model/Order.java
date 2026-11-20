package com.example.jora.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Order entity stored in the GeminiDB (DynamoDB-compatible) table.
 * Attribute layout: orderId(S), customerId(S), preTaxAmount(N),
 * postTaxAmount(N), version(N).
 */
public class Order {
    private String orderId;
    private String customerId;
    private BigDecimal preTaxAmount;
    private BigDecimal postTaxAmount;
    private Long version;

    public Order() {
    }

    public Order(String orderId, String customerId, BigDecimal preTaxAmount,
                 BigDecimal postTaxAmount, Long version) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.preTaxAmount = preTaxAmount;
        this.postTaxAmount = postTaxAmount;
        this.version = version;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getPreTaxAmount() {
        return preTaxAmount;
    }

    public void setPreTaxAmount(BigDecimal preTaxAmount) {
        this.preTaxAmount = preTaxAmount;
    }

    public BigDecimal getPostTaxAmount() {
        return postTaxAmount;
    }

    public void setPostTaxAmount(BigDecimal postTaxAmount) {
        this.postTaxAmount = postTaxAmount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    /** Converts this order into a DynamoDB attribute map. */
    public Map<String, Object> toAttributes() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderId", attr("S", orderId));
        item.put("customerId", attr("S", customerId));
        item.put("preTaxAmount", attr("N", preTaxAmount.toPlainString()));
        item.put("postTaxAmount", attr("N", postTaxAmount.toPlainString()));
        item.put("version", attr("N", version.toString()));
        return item;
    }

    /** Builds an order from a DynamoDB attribute map. */
    public static Order fromAttributes(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        Order order = new Order();
        order.orderId = requireString(item, "orderId");
        order.customerId = requireString(item, "customerId");
        order.preTaxAmount = new BigDecimal(requireString(item, "preTaxAmount"));
        order.postTaxAmount = new BigDecimal(requireString(item, "postTaxAmount"));
        order.version = Long.valueOf(requireString(item, "version"));
        return order;
    }

    private static Map<String, Object> attr(String type, String value) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put(type, value);
        return attribute;
    }

    private static String requireString(Map<String, Object> item, String name) {
        Object attribute = item.get(name);
        if (!(attribute instanceof Map)) {
            throw new IllegalStateException("item did not have a " + name + " attribute");
        }
        Map<?, ?> typed = (Map<?, ?>) attribute;
        Object value = typed.get("S") != null ? typed.get("S") : typed.get("N");
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalStateException("item attribute " + name + " was not a non-empty string");
        }
        return (String) value;
    }
}
