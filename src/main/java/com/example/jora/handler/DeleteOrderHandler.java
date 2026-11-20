package com.example.jora.handler;

import com.example.jora.db.OrderDao;
import com.example.jora.db.OrderDaoFactory;
import com.example.jora.exception.OrderDoesNotExistException;
import com.example.jora.exception.TableDoesNotExistException;
import com.example.jora.exception.UnableToDeleteException;
import com.example.jora.model.Order;
import com.google.gson.Gson;
import com.huawei.services.runtime.Context;

import java.util.Map;

/** DELETE /orders/{order_id}: deletes an order and returns the removed item. */
public class DeleteOrderHandler {

    private static final Gson GSON = new Gson();
    private final OrderDao orderDao;

    public DeleteOrderHandler() {
        this(OrderDaoFactory.createFromEnv());
    }

    public DeleteOrderHandler(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        OrderEvent apiEvent = new OrderEvent(event);
        String orderId = apiEvent.pathParameter("order_id");
        if (Strings.isNullOrEmpty(orderId)) {
            return Responses.error(GSON, 400, "order_id was not set");
        }
        try {
            Order deleted = orderDao.deleteOrder(orderId);
            log(context, "deleted orderId=" + orderId);
            return Responses.json(200, GSON.toJson(deleted));
        } catch (OrderDoesNotExistException e) {
            return Responses.error(GSON, 404, e.getMessage());
        } catch (TableDoesNotExistException e) {
            return Responses.error(GSON, 400, e.getMessage());
        } catch (UnableToDeleteException e) {
            return Responses.error(GSON, 409, e.getMessage());
        } catch (IllegalStateException e) {
            return Responses.error(GSON, 500, e.getMessage());
        }
    }

    private static void log(Context context, String message) {
        String requestId = context == null ? "-" : context.getRequestID();
        System.out.println("[ORDER-REST] delete " + message + " requestId=" + requestId);
    }
}
