package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class FlightResponse {
    public String bookingId;
    public String status;
    public String message;
    public String flightNumber;

    public FlightResponse(String bookingId, String status, String message, String flightNumber) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.flightNumber = flightNumber;
    }
}

public class FlightHandler implements RequestHandler<Map<String, Object>, FlightResponse> {

    private final DynamoDbClient dynamoDb;
    private final String tableName = System.getenv().getOrDefault("FLIGHT_TABLE_NAME", "Travel_Flights");

    public FlightHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.of(regionStr))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public FlightHandler(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public FlightResponse handleRequest(Map<String, Object> input, Context context) {
        String action = (String) input.getOrDefault("action", "BOOK");

        if ("CANCEL".equalsIgnoreCase(action)) {
            return cancelFlight(input, context);
        } else {
            return bookFlight(input, context);
        }
    }

    private FlightResponse bookFlight(Map<String, Object> input, Context context) {
        String bookingId = (String) input.getOrDefault("bookingId", UUID.randomUUID().toString());
        String userId = (String) input.get("userId");
        String userEmail = (String) input.getOrDefault("userEmail", input.get("email"));
        String destination = (String) input.get("destination");

        context.getLogger().log("Booking Flights to: " + destination + ", bookingId: " + bookingId);

        if ("Mars".equalsIgnoreCase(destination)) {
            throw new RuntimeException("Flights to Mars are temporarily cancel worldwide. Try Later.");
        }

        String flightNumber = "FL-" + (int) (Math.random() * 1000);

        saveBookingStatusAndOutbox(bookingId, userId, userEmail, destination, flightNumber, "CONFIRMED");

        context.getLogger().log("Flight Confirmed: " + flightNumber);
        return new FlightResponse(bookingId, "SUCCESS", "Flight Booked", flightNumber);
    }

    private FlightResponse cancelFlight(Map<String, Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");
        String flightNumber = (String) input.get("flightNumber");

        context.getLogger().log("Compensating Transaction: Cancelling Flight for Booking ID: " + bookingId);

        updateStatusToCancelledAndOutbox(bookingId, flightNumber);
        return new FlightResponse(bookingId, "SUCCESS", "Flight Cancelled (Compensated)", flightNumber);
    }

    private void saveBookingStatusAndOutbox(String bookingId, String user, String userEmail, String dest, String flight, String status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(bookingId).build());

        if (user != null) item.put("user_id", AttributeValue.builder().s(user).build());
        if (userEmail != null) item.put("user_email", AttributeValue.builder().s(userEmail).build());
        if (dest != null) item.put("destination", AttributeValue.builder().s(dest).build());
        if (flight != null) item.put("flight_number", AttributeValue.builder().s(flight).build());
        item.put("status", AttributeValue.builder().s(status).build());

        Put flightPut = Put.builder()
                .tableName(tableName)
                .item(item)
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("userId", user);
        if (userEmail != null) outboxPayload.put("userEmail", userEmail);
        outboxPayload.put("destination", dest);
        outboxPayload.put("flightNumber", flight);
        outboxPayload.put("status", status);
        outboxPayload.put("eventType", "FLIGHT_BOOKED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "FLIGHT_BOOKED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(flightPut).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }

    private void updateStatusToCancelledAndOutbox(String bookingId, String flightNumber) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("booking_id", AttributeValue.builder().s(bookingId).build());

        Update flightUpdate = Update.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #s = :newStatus")
                .expressionAttributeNames(Collections.singletonMap("#s", "status"))
                .expressionAttributeValues(Collections.singletonMap(":newStatus", AttributeValue.builder().s("CANCELLED").build()))
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("flightNumber", flightNumber);
        outboxPayload.put("status", "CANCELLED");
        outboxPayload.put("eventType", "FLIGHT_CANCELLED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "FLIGHT_CANCELLED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().update(flightUpdate).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }
}
