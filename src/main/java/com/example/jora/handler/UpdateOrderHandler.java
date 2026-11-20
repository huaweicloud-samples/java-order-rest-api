package com.example.jora.handler;

import com.example.jora.db.OrderDao;
import com.example.jora.db.OrderDaoFactory;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.exception.UnableToUpdateException;
import com.example.jora.model.Order;
import com.example.jora.model.UpdateOrderRequest;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.huawei.services.runtime.Context;

import java.util.Map;

/** POST /orders/{order_id}: updates an order with optimistic locking. */
public class UpdateOrderHandler {

    private static final Gson GSON = new Gson();
    private final OrderDao orderDao;

    public UpdateOrderHandler() {
        this(OrderDaoFactory.createFromEnv());
    }

    public UpdateOrderHandler(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        OrderEvent apiEvent = new OrderEvent(event);
        String orderId = apiEvent.pathParameter("order_id");
        if (Strings.isNullOrEmpty(orderId)) {
            return Responses.error(GSON, 400, "order_id was not set");
        }
        String body = apiEvent.body();
        if (body == null) {
            return Responses.error(GSON, 400, "Body was null");
        }
        final UpdateOrderRequest request;
        try {
            request = GSON.fromJson(body, UpdateOrderRequest.class);
        } catch (JsonSyntaxException e) {
            return Responses.error(GSON, 400, "Invalid JSON in body: " + e.getMessage());
        }
        if (request == null) {
            return Responses.error(GSON, 400, "Request was null");
        }
        try {
            Order updated = orderDao.updateOrder(new Order(orderId,
                    request.getCustomerId(), request.getPreTaxAmount(),
                    request.getPostTaxAmount(), request.getVersion()));
            log(context, "updated orderId=" + orderId + " version=" + updated.getVersion());
            return Responses.json(200, GSON.toJson(updated));
        } catch (UnableToUpdateException e) {
            return Responses.error(GSON, 409, e.getMessage());
        } catch (TableDoesNotExistException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (IllegalStateException e) {
            return Responses.error(GSON, 500, e.getMessage());
        }
    }

    private static void log(Context context, String message) {
        String requestId = context == null ? "-" : context.getRequestID();
        System.out.println("[ORDER-REST] update " + message + " requestId=" + requestId);
    }
}
