package com.example.jora.handler;

import com.example.jora.db.OrderDao;
import com.example.jora.db.OrderDaoFactory;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.model.OrderPage;
import com.example.jora.model.response.GetOrdersResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.huawei.services.runtime.Context;

import java.util.Map;

/** GET /orders: lists orders one page at a time (?exclusive_start_key=...). */
public class GetOrdersHandler {

    // serializeNulls keeps an explicit "lastEvaluatedKey": null on the final page.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final OrderDao orderDao;

    public GetOrdersHandler() {
        this(OrderDaoFactory.createFromEnv());
    }

    public GetOrdersHandler(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        OrderEvent apiEvent = new OrderEvent(event);
        String exclusiveStartKey = apiEvent.queryParameter("exclusive_start_key");
        try {
            OrderPage page = orderDao.getOrders(exclusiveStartKey);
            log(context, "listed " + page.getOrders().size() + " orders");
            return Responses.json(200, GSON.toJson(
                    new GetOrdersResponse(page.getLastEvaluatedKey(), page.getOrders())));
        } catch (TableDoesNotExistException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (IllegalStateException e) {
            return Responses.error(GSON, 500, e.getMessage());
        }
    }

    private static void log(Context context, String message) {
        String requestId = context == null ? "-" : context.getRequestID();
        System.out.println("[ORDER-REST] list " + message + " requestId=" + requestId);
    }
}
