package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Walks the real ASL (../infrastructure/step-function.json.example) against the real handlers and an
 * in-memory DynamoDB, injecting task failures per state, to check how the saga compensates.
 */
public class SagaCompensationTest {

    static final ObjectMapper M = new ObjectMapper();
    static final int PERMANENT = Integer.MAX_VALUE;

    JsonNode asl;
    Map<String, Map<String, Map<String, AttributeValue>>> db; // table -> booking_id -> item
    Map<String, Object> handlers;
    Context ctx;
    List<String> path;

    @BeforeEach
    void setUp() throws Exception {
        asl = M.readTree(new File("../infrastructure/step-function.json.example"));
        db = new HashMap<>();
        path = new ArrayList<>();
        DynamoDbClient ddb = mock(DynamoDbClient.class);
        when(ddb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(inv -> {
            TransactWriteItemsRequest r = inv.getArgument(0);
            for (TransactWriteItem t : r.transactItems()) {
                if (t.put() != null) {
                    Map<String, AttributeValue> item = t.put().item();
                    String key = item.containsKey("booking_id") ? item.get("booking_id").s() : item.get("eventId").s();
                    db.computeIfAbsent(t.put().tableName(), k -> new HashMap<>()).put(key, new HashMap<>(item));
                } else if (t.update() != null) { // handlers only use "SET #s = :newStatus" (UpdateItem upserts)
                    String key = t.update().key().get("booking_id").s();
                    db.computeIfAbsent(t.update().tableName(), k -> new HashMap<>())
                            .computeIfAbsent(key, k -> new HashMap<>(t.update().key()))
                            .put("status", t.update().expressionAttributeValues().get(":newStatus"));
                }
            }
            return TransactWriteItemsResponse.builder().build();
        });
        ctx = mock(Context.class);
        when(ctx.getLogger()).thenReturn(mock(LambdaLogger.class));
        handlers = Map.of("TravelPaymentFunction", new PaymentHandler(ddb),
                "TravelFlightFunction", new FlightHandler(ddb),
                "TravelHotelFunction", new HotelHandler(ddb));
    }

    /**
     * Minimal ASL interpreter for the subset this definition uses: Task (Parameters, ResultPath, Retry, Catch),
     * Choice (IsPresent), Fail, Succeed. failures[state] = how many attempts of that state throw before one succeeds.
     * shortcut: Retry/Catch match on States.ALL only and backoff is not slept, extend if the ASL names specific errors.
     * Returns "SUCCEEDED", "FAILED(<Error>)" for a Fail state, or "FAILED(States.TaskFailed)" for an uncaught error.
     */
    String run(Map<String, Object> input, Map<String, Integer> failures) throws Exception {
        ObjectNode data = M.valueToTree(input);
        Map<String, Integer> remaining = new HashMap<>(failures);
        String cur = asl.get("StartAt").asText();
        while (true) {
            path.add(cur);
            JsonNode s = asl.get("States").get(cur);
            switch (s.get("Type").asText()) {
                case "Succeed": return "SUCCEEDED";
                case "Fail": return "FAILED(" + s.get("Error").asText() + ")";
                case "Choice": cur = choose(s, data); continue;
                case "Task": break;
                default: throw new IllegalStateException("unsupported state type in " + cur);
            }
            Map<String, Object> params = new HashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = s.get("Parameters").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().endsWith(".$")) {
                    JsonNode v = at(data, e.getValue().asText());
                    if (v.isMissingNode()) throw new IllegalStateException("States.Runtime: " + e.getValue() + " in " + cur);
                    params.put(e.getKey().substring(0, e.getKey().length() - 2), M.treeToValue(v, Object.class));
                } else params.put(e.getKey(), e.getValue().asText());
            }
            Object handler = handlers.get(s.get("Resource").asText().replaceAll(".*:function:", ""));
            int maxRetries = s.has("Retry") ? s.get("Retry").get(0).get("MaxAttempts").asInt(3) : 0;
            Exception error = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (remaining.merge(cur, -1, Integer::sum) >= 0)
                        throw new RuntimeException("injected transient error in " + cur);
                    Object out = handler.getClass().getMethod("handleRequest", Map.class, Context.class).invoke(handler, params, ctx);
                    data.set(field(s.get("ResultPath")), M.valueToTree(out));
                    error = null;
                    break;
                } catch (Exception ex) {
                    error = ex;
                }
            }
            if (error == null) {
                cur = s.get("Next").asText();
            } else if (s.has("Catch")) {
                JsonNode c = s.get("Catch").get(0);
                data.put(field(c.get("ResultPath")), String.valueOf(error));
                cur = c.get("Next").asText();
            } else {
                return "FAILED(States.TaskFailed)";
            }
        }
    }

    String choose(JsonNode s, JsonNode data) {
        for (JsonNode rule : s.get("Choices")) {
            if (!rule.has("IsPresent")) throw new IllegalStateException("unsupported Choice rule " + rule);
            if (!at(data, rule.get("Variable").asText()).isMissingNode() == rule.get("IsPresent").asBoolean())
                return rule.get("Next").asText();
        }
        return s.get("Default").asText();
    }

    static JsonNode at(JsonNode data, String jsonPath) {
        return data.at(jsonPath.substring(1).replace('.', '/'));
    }

    static String field(JsonNode resultPath) {
        return resultPath.asText().substring(2); // "$.x" -> "x"
    }

    Map<String, Object> input(String bookingId, String destination, int amount) {
        return new HashMap<>(Map.of("userId", "u1", "userEmail", "u1@example.com",
                "bookingId", bookingId, "destination", destination, "amount", amount));
    }

    String status(String table, String bookingId) {
        Map<String, AttributeValue> item = db.getOrDefault(table, Map.of()).get(bookingId);
        return item == null ? "<none>" : item.get("status").s();
    }

    /** States the execution ran work in or ended at; Choice states route only, so they are left out. */
    List<String> taskAndTerminalStates() {
        return path.stream().filter(n -> !asl.get("States").get(n).get("Type").asText().equals("Choice"))
                .collect(Collectors.toList());
    }

    String outcome(String result, String id) {
        System.out.println("path=" + path + " result=" + result);
        return result + " path=" + path + " payment=" + status("Travel_Payments", id)
                + " flight=" + status("Travel_Flights", id) + " hotel=" + status("Travel_Hotels", id);
    }

    @Test
    void transientUndoFlightFailureIsRetriedAndRollbackCompletes() throws Exception {
        String r = run(input("b1", "Atlantis", 200), Map.of("Undo Flight", 1));
        String why = outcome(r, "b1");
        assertEquals("FAILED(SagaRollbackComplete)", r, why);
        assertEquals("CANCELLED", status("Travel_Flights", "b1"), why);
        assertEquals("REFUNDED", status("Travel_Payments", "b1"), why);
    }

    @Test
    void permanentUndoFlightFailureStillRefundsAndEndsCompensationFailed() throws Exception {
        String r = run(input("b2", "Atlantis", 200), Map.of("Undo Flight", PERMANENT));
        String why = outcome(r, "b2");
        assertEquals("REFUNDED", status("Travel_Payments", "b2"), why);
        assertEquals("FAILED(CompensationFailed)", r, why);
    }

    @Test
    void permanentRefundFailureEndsCompensationFailed() throws Exception {
        String r = run(input("b3", "Mars", 200), Map.of("Refund Payment", PERMANENT));
        assertEquals("FAILED(CompensationFailed)", r, outcome(r, "b3"));
    }

    @Test
    void happyPathUnchanged() throws Exception {
        String r = run(input("b4", "Paris", 200), Map.of());
        String why = outcome(r, "b4");
        assertEquals("SUCCEEDED", r, why);
        assertEquals(List.of("Process Payment", "Book Flight", "Book Hotel", "Booking Success"), taskAndTerminalStates(), why);
        assertEquals("PAYMENT PROCESSED", status("Travel_Payments", "b4"), why);
        assertEquals("CONFIRMED", status("Travel_Flights", "b4"), why);
        assertEquals("CONFIRMED", status("Travel_Hotels", "b4"), why);
    }

    @Test
    void amountOver1000Unchanged() throws Exception {
        String r = run(input("b5", "Paris", 2000), Map.of());
        String why = outcome(r, "b5");
        assertEquals("FAILED(PaymentError)", r, why);
        assertEquals(List.of("Process Payment", "Payment Fail"), taskAndTerminalStates(), why);
        assertEquals("<none>", status("Travel_Payments", "b5"), why);
    }

    @Test
    void marsRollbackUnchanged() throws Exception {
        String r = run(input("b6", "Mars", 200), Map.of());
        String why = outcome(r, "b6");
        assertEquals("FAILED(SagaRollbackComplete)", r, why);
        assertEquals(List.of("Process Payment", "Book Flight", "Refund Payment", "Saga Fail"), taskAndTerminalStates(), why);
        assertEquals("REFUNDED", status("Travel_Payments", "b6"), why);
        assertEquals("<none>", status("Travel_Flights", "b6"), why);
    }

    @Test
    void atlantisRollbackUnchanged() throws Exception {
        String r = run(input("b7", "Atlantis", 200), Map.of());
        String why = outcome(r, "b7");
        assertEquals("FAILED(SagaRollbackComplete)", r, why);
        assertEquals(List.of("Process Payment", "Book Flight", "Book Hotel", "Undo Flight", "Refund Payment", "Saga Fail"),
                taskAndTerminalStates(), why);
        assertEquals("REFUNDED", status("Travel_Payments", "b7"), why);
        assertEquals("CANCELLED", status("Travel_Flights", "b7"), why);
        assertEquals("<none>", status("Travel_Hotels", "b7"), why);
    }
}
