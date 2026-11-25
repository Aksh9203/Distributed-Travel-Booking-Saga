package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class HandlerActionTest {

    private DynamoDbClient dynamoDb;
    private Context context;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private Map<String, Object> input(String action) {
        Map<String, Object> input = new HashMap<>();
        if (action != null) input.put("action", action);
        input.put("bookingId", "b-1");
        input.put("userId", "u-1");
        input.put("userEmail", "u@example.com");
        input.put("destination", "Paris");
        input.put("amount", 100);
        input.put("transactionId", "TX-1");
        input.put("flightNumber", "FL-1");
        return input;
    }

    private void assertRejectedWithoutWrite(String action, Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().contains(action), e.getMessage());
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void paymentRejectsUnknownAction() {
        assertRejectedWithoutWrite("REFUD", () -> new PaymentHandler(dynamoDb).handleRequest(input("REFUD"), context));
    }

    @Test
    void flightRejectsUnknownAction() {
        assertRejectedWithoutWrite("CANCELL", () -> new FlightHandler(dynamoDb).handleRequest(input("CANCELL"), context));
    }

    @Test
    void hotelRejectsUnknownAction() {
        assertRejectedWithoutWrite("CANCELL", () -> new HotelHandler(dynamoDb).handleRequest(input("CANCELL"), context));
    }

    @Test
    void missingActionKeepsForwardDefaultAndMatchingIgnoresCase() {
        assertEquals("PAYMENT PROCESSED", new PaymentHandler(dynamoDb).handleRequest(input(null), context).message);
        assertEquals("Flight Booked", new FlightHandler(dynamoDb).handleRequest(input(null), context).message);
        assertEquals("Hotel Booked", new HotelHandler(dynamoDb).handleRequest(input(null), context).message);
        assertEquals("PAYMENT REFUNDED", new PaymentHandler(dynamoDb).handleRequest(input("refund"), context).message);
        assertEquals("Hotel Cancelled (Compensated)", new HotelHandler(dynamoDb).handleRequest(input("cancel"), context).message);
    }

    // Feeds every action the shipped state machine sends to the handler it targets.
    @Test
    void everyStateMachineActionReachesItsPath() throws Exception {
        Map<String, Function<Map<String, Object>, String>> handlers = Map.of(
                "TravelPaymentFunction", in -> new PaymentHandler(dynamoDb).handleRequest(in, context).message,
                "TravelFlightFunction", in -> new FlightHandler(dynamoDb).handleRequest(in, context).message,
                "TravelHotelFunction", in -> new HotelHandler(dynamoDb).handleRequest(in, context).message);
        Map<String, String> expected = Map.of(
                "TravelPaymentFunction PAY", "PAYMENT PROCESSED",
                "TravelPaymentFunction REFUND", "PAYMENT REFUNDED",
                "TravelFlightFunction BOOK", "Flight Booked",
                "TravelFlightFunction CANCEL", "Flight Cancelled (Compensated)",
                "TravelHotelFunction BOOK", "Hotel Booked",
                "TravelHotelFunction CANCEL", "Hotel Cancelled (Compensated)");

        JsonNode states = new ObjectMapper()
                .readTree(Path.of("../infrastructure/step-function.json.example").toFile())
                .get("States");
        List<String> seen = new ArrayList<>();
        for (Map.Entry<String, JsonNode> state : states.properties()) {
            JsonNode node = state.getValue();
            if (!"Task".equals(node.path("Type").asText())) continue;
            String resource = node.get("Resource").asText();
            String function = resource.substring(resource.lastIndexOf(':') + 1);
            String action = node.get("Parameters").get("action").asText();
            String key = function + " " + action;

            assertEquals(expected.get(key), handlers.get(function).apply(input(action)), state.getKey() + " -> " + key);
            seen.add(state.getKey() + " -> " + key);
        }
        System.out.println("ASL actions verified: " + seen);
        assertTrue(seen.size() >= 5, seen.toString());
    }
}
