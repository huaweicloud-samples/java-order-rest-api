package com.example.jora.handler;

import com.example.jora.db.OrderDao;
import com.example.jora.db.OrderDaoFactory;
import com.example.jora.exception.OrderDoesNotExistException;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.model.Order;
import com.google.gson.Gson;
import com.huawei.services.runtime.Context;

import java.util.Map;

/** GET /orders/{order_id}: reads a single order by id. */
public class GetOrderHandler {

    private static final Gson GSON = new Gson();
    private final OrderDao orderDao;

    public GetOrderHandler() {
        this(OrderDaoFactory.createFromEnv());
    }

    public GetOrderHandler(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        OrderEvent apiEvent = new OrderEvent(event);
        String orderId = apiEvent.pathParameter("order_id");
        if (Strings.isNullOrEmpty(orderId)) {
            return Responses.error(GSON, 400, "order_id was not set");
        }
        try {
            Order order = orderDao.getOrder(orderId);
            log(context, "read orderId=" + orderId);
            return Responses.json(200, GSON.toJson(order));
        } catch (OrderDoesNotExistException e) {
            return Responses.error(GSON, 404, e.getMessage());
        } catch (TableDoesNotExistException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (IllegalStateException e) {
            return Responses.error(GSON, 500, e.getMessage());
        }
    }

    private static void log(Context context, String message) {
        String requestId = context == null ? "-" : context.getRequestID();
        System.out.println("[ORDER-REST] get " + message + " requestId=" + requestId);
    }
}
