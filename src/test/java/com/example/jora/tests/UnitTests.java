package com.example.jora.tests;

import com.example.jora.db.OrderDao;
import com.example.jora.exception.OrderDoesNotExistException;
import com.example.jora.exception.UnableToUpdateException;
import com.example.jora.handler.CreateOrderHandler;
import com.example.jora.handler.DeleteOrderHandler;
import com.example.jora.handler.GetOrderHandler;
import com.example.jora.handler.GetOrdersHandler;
import com.example.jora.handler.OrderEvent;
import com.example.jora.handler.Responses;
import com.example.jora.handler.UpdateOrderHandler;
import com.example.jora.model.CreateOrderRequest;
import com.example.jora.model.Order;
import com.example.jora.model.OrderPage;
import com.example.jora.model.UpdateOrderRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Framework-free unit tests for DTOs, event parsing and the order data layer. */
public final class UnitTests {

    private static final Gson GSON = new Gson();
    private static int passed;
    private static int failed;

    private interface TestCase {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        run("dto order json round trip", UnitTests::testOrderRoundTrip);
        run("dto create request parses numbers", UnitTests::testCreateRequestParse);
        run("dto update request parses decimals", UnitTests::testUpdateRequestParse);
        run("response carries apig shape", UnitTests::testResponseShape);
        run("event decodes base64 body", UnitTests::testEventBase64Body);
        run("event reads params and plain body", UnitTests::testEventParams);
        run("dao create assigns id and version 1", UnitTests::testDaoCreate);
        run("dao get returns created order", UnitTests::testDaoGet);
        run("dao get missing throws 404 error", UnitTests::testDaoGetMissing);
        run("dao update increments version", UnitTests::testDaoUpdate);
        run("dao update stale version rejected", UnitTests::testDaoUpdateStaleVersion);
        run("dao update missing order rejected", UnitTests::testDaoUpdateMissing);
        run("dao update null amount rejected", UnitTests::testDaoUpdateNullAmount);
        run("dao delete returns old item", UnitTests::testDaoDelete);
        run("dao delete missing throws", UnitTests::testDaoDeleteMissing);
        run("dao scan paginates", UnitTests::testDaoScanPagination);
        run("handler create rejects missing customerId", UnitTests::testCreateHandlerValidation);
        run("handler create returns 201", UnitTests::testCreateHandlerSuccess);
        run("handler get returns 404", UnitTests::testGetOrderHandler404);
        run("handler update returns 409 on version mismatch", UnitTests::testUpdateHandler409);
        run("handler delete returns removed order", UnitTests::testDeleteHandler);
        run("handler list paginates", UnitTests::testGetOrdersHandlerPagination);

        System.out.printf("passed=%d failed=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("[FAIL] " + name + ": " + e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, TestCase block, String message) {
        try {
            block.run();
        } catch (Throwable e) {
            if (expected.isInstance(e)) {
                return;
            }
            throw new AssertionError(message + " but got " + e);
        }
        throw new AssertionError(message + " but nothing was thrown");
    }

    private static OrderDao newDao(FakeGeminiClient client) {
        return new OrderDao(client, "orders_unit", 10);
    }

    private static Map<String, Object> event(String bodyJson, boolean base64) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (bodyJson != null) {
            String raw = base64
                    ? Base64.getEncoder().encodeToString(bodyJson.getBytes(StandardCharsets.UTF_8))
                    : bodyJson;
            event.put("body", raw);
            event.put("isBase64Encoded", base64);
        }
        return event;
    }

    private static Map<String, Object> withPathParameter(Map<String, Object> event, String orderId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("order_id", orderId);
        event.put("pathParameters", parameters);
        return event;
    }

    private static Map<String, Object> withQueryParameter(Map<String, Object> event, String key, String value) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(key, value);
        event.put("queryStringParameters", parameters);
        return event;
    }

    private static String bodyOf(Map<String, Object> response) {
        return (String) response.get("body");
    }

    // ------------------------------------------------------------------ DTOs

    private static void testOrderRoundTrip() {
        Order order = new Order("o-1", "c-1", new BigDecimal("12.34"), new BigDecimal("13.00"), 3L);
        Order parsed = GSON.fromJson(GSON.toJson(order), Order.class);
        assertEquals("o-1", parsed.getOrderId(), "orderId");
        assertEquals("c-1", parsed.getCustomerId(), "customerId");
        assertEquals(0, new BigDecimal("12.34").compareTo(parsed.getPreTaxAmount()), "preTaxAmount");
        assertEquals(0, new BigDecimal("13.00").compareTo(parsed.getPostTaxAmount()), "postTaxAmount");
        assertEquals(Long.valueOf(3L), parsed.getVersion(), "version");
    }

    private static void testCreateRequestParse() {
        CreateOrderRequest request = GSON.fromJson(
                "{\"customerId\":\"c-1\",\"preTaxAmount\":100,\"postTaxAmount\":120}",
                CreateOrderRequest.class);
        assertEquals("c-1", request.getCustomerId(), "customerId");
        assertEquals(Long.valueOf(100L), request.getPreTaxAmount(), "preTaxAmount");
        assertEquals(Long.valueOf(120L), request.getPostTaxAmount(), "postTaxAmount");
    }

    private static void testUpdateRequestParse() {
        UpdateOrderRequest request = GSON.fromJson(
                "{\"customerId\":\"c-1\",\"preTaxAmount\":12.34,\"postTaxAmount\":56.78,\"version\":2}",
                UpdateOrderRequest.class);
        assertEquals(0, new BigDecimal("12.34").compareTo(request.getPreTaxAmount()), "preTaxAmount");
        assertEquals(0, new BigDecimal("56.78").compareTo(request.getPostTaxAmount()), "postTaxAmount");
        assertEquals(Long.valueOf(2L), request.getVersion(), "version");
    }

    private static void testResponseShape() {
        Map<String, Object> response = Responses.json(201, "{\"orderId\":\"o-1\"}");
        assertEquals(201, response.get("statusCode"), "statusCode");
        assertEquals(false, response.get("isBase64Encoded"), "isBase64Encoded");
        assertEquals("{\"orderId\":\"o-1\"}", response.get("body"), "body");
        Object headers = response.get("headers");
        check(headers instanceof Map && "application/json".equals(((Map<?, ?>) headers).get("Content-Type")),
                "headers Content-Type");
    }

    // ---------------------------------------------------------------- events

    private static void testEventBase64Body() {
        OrderEvent orderEvent = new OrderEvent(event("{\"customerId\":\"c-1\"}", true));
        assertEquals("{\"customerId\":\"c-1\"}", orderEvent.body(), "decoded body");
    }

    private static void testEventParams() {
        Map<String, Object> raw = event("{\"x\":1}", false);
        withPathParameter(raw, "o-9");
        withQueryParameter(raw, "exclusive_start_key", "o-1");
        OrderEvent orderEvent = new OrderEvent(raw);
        assertEquals("o-9", orderEvent.pathParameter("order_id"), "path parameter");
        assertEquals("o-1", orderEvent.queryParameter("exclusive_start_key"), "query parameter");
        assertEquals("{\"x\":1}", orderEvent.body(), "plain body");
    }

    // ------------------------------------------------------------------- dao

    private static void testDaoCreate() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order order = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        check(order.getOrderId() != null && !order.getOrderId().isEmpty(), "orderId assigned");
        assertEquals(Long.valueOf(1L), order.getVersion(), "initial version");
        assertEquals(0, BigDecimal.valueOf(100L).compareTo(order.getPreTaxAmount()), "preTaxAmount");
    }

    private static void testDaoGet() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order created = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        Order loaded = dao.getOrder(created.getOrderId());
        assertEquals(created.getOrderId(), loaded.getOrderId(), "orderId");
        assertEquals(created.getCustomerId(), loaded.getCustomerId(), "customerId");
        assertEquals(created.getVersion(), loaded.getVersion(), "version");
    }

    private static void testDaoGetMissing() {
        OrderDao dao = newDao(new FakeGeminiClient());
        assertThrows(OrderDoesNotExistException.class,
                () -> dao.getOrder("missing"), "missing order should raise OrderDoesNotExistException");
    }

    private static void testDaoUpdate() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order created = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        Order updated = dao.updateOrder(new Order(created.getOrderId(), "c-2",
                new BigDecimal("200"), new BigDecimal("240"), created.getVersion()));
        assertEquals(Long.valueOf(2L), updated.getVersion(), "version incremented");
        assertEquals("c-2", updated.getCustomerId(), "customerId updated");
        Order loaded = dao.getOrder(created.getOrderId());
        assertEquals(Long.valueOf(2L), loaded.getVersion(), "persisted version");
        assertEquals(0, new BigDecimal("240").compareTo(loaded.getPostTaxAmount()), "persisted amount");
    }

    private static void testDaoUpdateStaleVersion() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order created = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        assertThrows(UnableToUpdateException.class,
                () -> dao.updateOrder(new Order(created.getOrderId(), "c-2",
                        new BigDecimal("200"), new BigDecimal("240"), 99L)),
                "stale version should be rejected");
    }

    private static void testDaoUpdateMissing() {
        OrderDao dao = newDao(new FakeGeminiClient());
        assertThrows(UnableToUpdateException.class,
                () -> dao.updateOrder(new Order("missing", "c-1",
                        new BigDecimal("1"), new BigDecimal("1"), 1L)),
                "missing order update should be rejected");
    }

    private static void testDaoUpdateNullAmount() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order created = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        assertThrows(IllegalArgumentException.class,
                () -> dao.updateOrder(new Order(created.getOrderId(), "c-1",
                        null, new BigDecimal("1"), created.getVersion())),
                "null preTaxAmount should be rejected");
    }

    private static void testDaoDelete() {
        OrderDao dao = newDao(new FakeGeminiClient());
        Order created = dao.createOrder(new CreateOrderRequest("c-1", 100L, 120L));
        Order deleted = dao.deleteOrder(created.getOrderId());
        assertEquals(created.getOrderId(), deleted.getOrderId(), "deleted item returned");
        assertThrows(OrderDoesNotExistException.class,
                () -> dao.getOrder(created.getOrderId()), "order gone after delete");
    }

    private static void testDaoDeleteMissing() {
        OrderDao dao = newDao(new FakeGeminiClient());
        assertThrows(OrderDoesNotExistException.class,
                () -> dao.deleteOrder("missing"), "delete missing should raise");
    }

    private static void testDaoScanPagination() {
        FakeGeminiClient client = new FakeGeminiClient();
        OrderDao dao = new OrderDao(client, "orders_unit", 10);
        for (int i = 0; i < 25; i++) {
            dao.createOrder(new CreateOrderRequest("c-" + i, (long) i, (long) (i + 1)));
        }
        OrderPage first = dao.getOrders(null);
        assertEquals(10, first.getOrders().size(), "first page size");
        check(first.getLastEvaluatedKey() != null, "first page has continuation key");
        OrderPage second = dao.getOrders(first.getLastEvaluatedKey());
        assertEquals(10, second.getOrders().size(), "second page size");
        check(second.getLastEvaluatedKey() != null, "second page has continuation key");
        OrderPage third = dao.getOrders(second.getLastEvaluatedKey());
        assertEquals(5, third.getOrders().size(), "third page size");
        check(third.getLastEvaluatedKey() == null, "third page is final");
    }

    // --------------------------------------------------------------- handlers

    private static void testCreateHandlerValidation() {
        CreateOrderHandler handler = new CreateOrderHandler(newDao(new FakeGeminiClient()));
        Map<String, Object> response = handler.handleRequest(
                event("{\"preTaxAmount\":1,\"postTaxAmount\":2}", true), null);
        assertEquals(400, response.get("statusCode"), "missing customerId status");
        JsonObject error = JsonParser.parseString(bodyOf(response)).getAsJsonObject();
        check(error.get("message").getAsString().contains("customerId"), "error message");
    }

    private static void testCreateHandlerSuccess() {
        CreateOrderHandler handler = new CreateOrderHandler(newDao(new FakeGeminiClient()));
        Map<String, Object> response = handler.handleRequest(
                event("{\"customerId\":\"c-1\",\"preTaxAmount\":100,\"postTaxAmount\":120}", true), null);
        assertEquals(201, response.get("statusCode"), "created status");
        JsonObject body = JsonParser.parseString(bodyOf(response)).getAsJsonObject();
        check(body.has("orderId") && !body.get("orderId").getAsString().isEmpty(), "orderId present");
        assertEquals(1, body.get("version").getAsInt(), "initial version");
    }

    private static void testGetOrderHandler404() {
        GetOrderHandler handler = new GetOrderHandler(newDao(new FakeGeminiClient()));
        Map<String, Object> response = handler.handleRequest(
                withPathParameter(event(null, false), "missing"), null);
        assertEquals(404, response.get("statusCode"), "missing order status");
    }

    private static void testUpdateHandler409() {
        FakeGeminiClient client = new FakeGeminiClient();
        OrderDao dao = newDao(client);
        CreateOrderHandler creator = new CreateOrderHandler(dao);
        Map<String, Object> created = creator.handleRequest(
                event("{\"customerId\":\"c-1\",\"preTaxAmount\":100,\"postTaxAmount\":120}", true), null);
        String orderId = JsonParser.parseString(bodyOf(created)).getAsJsonObject().get("orderId").getAsString();
        UpdateOrderHandler handler = new UpdateOrderHandler(dao);
        Map<String, Object> response = handler.handleRequest(withPathParameter(
                event("{\"customerId\":\"c-1\",\"preTaxAmount\":2,\"postTaxAmount\":3,\"version\":7}", true),
                orderId), null);
        assertEquals(409, response.get("statusCode"), "version mismatch status");
    }

    private static void testDeleteHandler() {
        FakeGeminiClient client = new FakeGeminiClient();
        OrderDao dao = newDao(client);
        CreateOrderHandler creator = new CreateOrderHandler(dao);
        Map<String, Object> created = creator.handleRequest(
                event("{\"customerId\":\"c-1\",\"preTaxAmount\":100,\"postTaxAmount\":120}", true), null);
        String orderId = JsonParser.parseString(bodyOf(created)).getAsJsonObject().get("orderId").getAsString();
        DeleteOrderHandler deleter = new DeleteOrderHandler(dao);
        Map<String, Object> response = deleter.handleRequest(
                withPathParameter(event(null, false), orderId), null);
        assertEquals(200, response.get("statusCode"), "delete status");
        assertEquals(orderId, JsonParser.parseString(bodyOf(response)).getAsJsonObject()
                .get("orderId").getAsString(), "deleted order returned");
        GetOrderHandler reader = new GetOrderHandler(dao);
        Map<String, Object> afterDelete = reader.handleRequest(
                withPathParameter(event(null, false), orderId), null);
        assertEquals(404, afterDelete.get("statusCode"), "read after delete status");
    }

    private static void testGetOrdersHandlerPagination() {
        FakeGeminiClient client = new FakeGeminiClient();
        OrderDao dao = new OrderDao(client, "orders_unit", 10);
        for (int i = 0; i < 15; i++) {
            dao.createOrder(new CreateOrderRequest("c-" + i, (long) i, (long) (i + 1)));
        }
        GetOrdersHandler handler = new GetOrdersHandler(dao);
        Map<String, Object> first = handler.handleRequest(event(null, false), null);
        assertEquals(200, first.get("statusCode"), "list status");
        JsonObject firstBody = JsonParser.parseString(bodyOf(first)).getAsJsonObject();
        assertEquals(10, firstBody.get("orders").getAsJsonArray().size(), "first page count");
        String continuation = firstBody.get("lastEvaluatedKey").getAsString();
        check(continuation != null && !continuation.isEmpty(), "continuation key");
        Map<String, Object> second = handler.handleRequest(
                withQueryParameter(event(null, false), "exclusive_start_key", continuation), null);
        JsonObject secondBody = JsonParser.parseString(bodyOf(second)).getAsJsonObject();
        assertEquals(5, secondBody.get("orders").getAsJsonArray().size(), "second page count");
        com.google.gson.JsonElement finalKey = secondBody.get("lastEvaluatedKey");
        check(finalKey == null || finalKey.isJsonNull(), "final page has no continuation key");
    }
}
