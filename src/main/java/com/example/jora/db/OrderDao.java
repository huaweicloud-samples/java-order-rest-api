package com.example.jora.db;

import com.example.jora.exception.CouldNotCreateOrderException;
import com.example.jora.exception.OrderDoesNotExistException;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.exception.UnableToDeleteException;
import com.example.jora.exception.UnableToUpdateException;
import com.example.jora.model.CreateOrderRequest;
import com.example.jora.model.Order;
import com.example.jora.model.OrderPage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order persistence layer on top of the GeminiDB DynamoDB-compatible interface.
 * Uses only whole-item reads and writes (GetItem/PutItem/DeleteItem/Scan);
 * optimistic locking is implemented as read-check-write on the version attribute.
 */
public class OrderDao {

    private static final String ORDER_ID = "orderId";
    private static final String RESOURCE_NOT_FOUND = "ResourceNotFoundException";
    private static final String PRE_TAX_AMOUNT_WAS_NULL = "preTaxAmount was null";
    private static final String POST_TAX_AMOUNT_WAS_NULL = "postTaxAmount was null";
    private static final String VERSION_WAS_NULL = "version was null";

    private final GeminiClient client;
    private final String tableName;
    private final int pageSize;

    public OrderDao(GeminiClient client, String tableName, int pageSize) {
        this.client = client;
        this.tableName = tableName;
        this.pageSize = pageSize;
    }

    /** Returns an order or throws when it does not exist. */
    public Order getOrder(String orderId) {
        Map<String, Object> item;
        try {
            item = client.getItem(tableName, key(orderId));
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName + " does not exist");
            }
            throw e;
        }
        if (item == null) {
            throw new OrderDoesNotExistException("Order " + orderId + " does not exist");
        }
        return Order.fromAttributes(item);
    }

    /** Gets a page of orders, at most pageSize long. */
    public OrderPage getOrders(String exclusiveStartOrderId) {
        Map<String, Object> exclusiveStartKey = null;
        if (!isNullOrEmpty(exclusiveStartOrderId)) {
            exclusiveStartKey = key(exclusiveStartOrderId);
        }
        ScanPage page;
        try {
            page = client.scan(tableName, pageSize, exclusiveStartKey);
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName + " does not exist");
            }
            throw e;
        }

        List<Order> orders = new ArrayList<>();
        for (Map<String, Object> item : page.getItems()) {
            orders.add(Order.fromAttributes(item));
        }

        String lastEvaluatedKey = null;
        if (page.getLastEvaluatedKey() != null) {
            Object value = page.getLastEvaluatedKey().get(ORDER_ID);
            String keyOrder = value instanceof Map ? stringValue(((Map<?, ?>) value).get("S")) : null;
            if (isNullOrEmpty(keyOrder)) {
                throw new IllegalStateException(
                        "orderId did not exist or was not a non-empty string in the lastEvaluatedKey");
            }
            lastEvaluatedKey = keyOrder;
        }
        return new OrderPage(orders, lastEvaluatedKey);
    }

    /**
     * Updates an order with optimistic locking: the current item is read, the
     * requested version must match the stored one, then the whole item is
     * written back with version incremented by one.
     */
    public Order updateOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order to update was null");
        }
        if (isNullOrEmpty(order.getOrderId())) {
            throw new IllegalArgumentException("orderId was null or empty");
        }
        validateCustomerId(order.getCustomerId());
        if (order.getPreTaxAmount() == null) {
            throw new IllegalArgumentException(PRE_TAX_AMOUNT_WAS_NULL);
        }
        if (order.getPostTaxAmount() == null) {
            throw new IllegalArgumentException(POST_TAX_AMOUNT_WAS_NULL);
        }
        if (order.getVersion() == null) {
            throw new IllegalArgumentException(VERSION_WAS_NULL);
        }

        Map<String, Object> current;
        try {
            current = client.getItem(tableName, key(order.getOrderId()));
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName
                        + " does not exist and was deleted after reading the order");
            }
            throw e;
        }
        if (current == null || !order.getVersion().equals(Order.fromAttributes(current).getVersion())) {
            throw new UnableToUpdateException(
                    "Either the order did not exist or the provided version was not current");
        }

        Order updated = new Order(order.getOrderId(), order.getCustomerId(),
                order.getPreTaxAmount(), order.getPostTaxAmount(), order.getVersion() + 1);
        try {
            client.putItem(tableName, updated.toAttributes());
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName
                        + " does not exist and was deleted after reading the order");
            }
            throw new UnableToUpdateException("Unable to persist the updated order: " + e.getMessage());
        }
        return updated;
    }

    /** Deletes an order and returns the deleted item. */
    public Order deleteOrder(String orderId) {
        Map<String, Object> existing;
        try {
            existing = client.getItem(tableName, key(orderId));
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName
                        + " does not exist and was deleted after reading the order");
            }
            throw e;
        }
        if (existing == null) {
            throw new OrderDoesNotExistException("Order " + orderId + " does not exist");
        }
        try {
            client.deleteItem(tableName, key(orderId));
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName
                        + " does not exist and was deleted after reading the order");
            }
            throw new UnableToDeleteException("Unable to delete the order: " + e.getMessage());
        }
        return Order.fromAttributes(existing);
    }

    /** Creates an order with a random id and initial version 1. */
    public Order createOrder(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("CreateOrderRequest was null");
        }
        validateCustomerId(request.getCustomerId());
        if (request.getPreTaxAmount() == null) {
            throw new IllegalArgumentException(PRE_TAX_AMOUNT_WAS_NULL);
        }
        if (request.getPostTaxAmount() == null) {
            throw new IllegalArgumentException(POST_TAX_AMOUNT_WAS_NULL);
        }
        Order order = new Order(UUID.randomUUID().toString(), request.getCustomerId(),
                BigDecimal.valueOf(request.getPreTaxAmount()),
                BigDecimal.valueOf(request.getPostTaxAmount()), 1L);
        try {
            client.putItem(tableName, order.toAttributes());
        } catch (GeminiException e) {
            if (RESOURCE_NOT_FOUND.equals(e.getCode())) {
                throw new TableDoesNotExistException("Order table " + tableName + " does not exist");
            }
            throw new CouldNotCreateOrderException("Unable to create the order: " + e.getMessage());
        }
        return order;
    }

    private String validateCustomerId(String customerId) {
        if (isNullOrEmpty(customerId)) {
            throw new IllegalArgumentException("customerId was null or empty");
        }
        return customerId;
    }

    private static Map<String, Object> key(String orderId) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("S", orderId);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put(ORDER_ID, attribute);
        return key;
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
