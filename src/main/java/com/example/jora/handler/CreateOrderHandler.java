package com.example.jora.handler;

import com.example.jora.db.OrderDao;
import com.example.jora.db.OrderDaoFactory;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.model.CreateOrderRequest;
import com.example.jora.model.Order;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.huawei.services.runtime.Context;

import java.util.Map;

/** POST /orders: creates a new order from the JSON request body. */
public class CreateOrderHandler {

    private static final Gson GSON = new Gson();
    private final OrderDao orderDao;

    public CreateOrderHandler() {
        this(OrderDaoFactory.createFromEnv());
    }

    public CreateOrderHandler(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        OrderEvent apiEvent = new OrderEvent(event);
        String body = apiEvent.body();
        if (body == null) {
            return Responses.error(GSON, 400, "Body was null");
        }
        final CreateOrderRequest request;
        try {
            request = GSON.fromJson(body, CreateOrderRequest.class);
        } catch (JsonSyntaxException e) {
            return Responses.error(GSON, 400, "Invalid JSON in body: " + e.getMessage());
        }
        if (request == null) {
            return Responses.error(GSON, 400, "Request was null");
        }
        if (Strings.isNullOrEmpty(request.getCustomerId())) {
            return Responses.error(GSON, 400, "Require customerId to create an order");
        }
        if (request.getPreTaxAmount() == null) {
            return Responses.error(GSON, 400, "Require preTaxAmount to create an order");
        }
        if (request.getPostTaxAmount() == null) {
            return Responses.error(GSON, 400, "Require postTaxAmount to create an order");
        }
        try {
            Order order = orderDao.createOrder(request);
            log(context, "created orderId=" + order.getOrderId());
            return Responses.json(201, GSON.toJson(order));
        } catch (TableDoesNotExistException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (IllegalStateException e) {
            // includes CouldNotCreateOrderException
            return Responses.error(GSON, 500, e.getMessage());
        }
    }

    private static void log(Context context, String message) {
        String requestId = context == null ? "-" : context.getRequestID();
        System.out.println("[ORDER-REST] create " + message + " requestId=" + requestId);
    }
}
