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

class HotelResponse {
    public String bookingId;
    public String status;
    public String message;
    public String hotelNumber;

    public HotelResponse(String bookingId, String status, String message, String hotelNumber) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.hotelNumber = hotelNumber;
    }
}

public class HotelHandler implements RequestHandler<Map<String, Object>, HotelResponse> {

    private final DynamoDbClient dynamoDb;
    private final String tableName = System.getenv().getOrDefault("HOTEL_TABLE_NAME", "Travel_Hotels");

    public HotelHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.of(regionStr))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public HotelHandler(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public HotelResponse handleRequest(Map<String, Object> input, Context context) {
        String action = (String) input.getOrDefault("action", "BOOK");

        if ("CANCEL".equalsIgnoreCase(action)) {
            return cancelHotel(input, context);
        } else {
            return bookHotel(input, context);
        }
    }

    private HotelResponse bookHotel(Map<String, Object> input, Context context) {
        String bookingId = (String) input.getOrDefault("bookingId", UUID.randomUUID().toString());
        String userId = (String) input.get("userId");
        String userEmail = (String) input.getOrDefault("userEmail", input.get("email"));
        String destination = (String) input.get("destination");

        context.getLogger().log("Hotel Confirmed: " + destination + ", bookingId: " + bookingId);

        if ("Atlantis".equalsIgnoreCase(destination)) {
            throw new RuntimeException("Hotel is full. Try Later");
        }

        String hotelNumber = "HL-" + (int) (Math.random() * 100);
        context.getLogger().log("Hotel Number is: " + hotelNumber);

        saveBookingStatusAndOutbox(bookingId, userId, userEmail, destination, hotelNumber, "CONFIRMED");
        return new HotelResponse(bookingId, "SUCCESS", "Hotel Booked", hotelNumber);
    }

    private HotelResponse cancelHotel(Map<String, Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");
        String hotelNumber = (String) input.getOrDefault("hotelNumber", (String) input.get("flightNumber"));

        context.getLogger().log("Compensating Transaction: Cancelling Hotel for Booking ID: " + bookingId);

        updateStatusToCancelledAndOutbox(bookingId, hotelNumber);
        return new HotelResponse(bookingId, "SUCCESS", "Hotel Cancelled (Compensated)", hotelNumber);
    }

    private void saveBookingStatusAndOutbox(String bookingId, String user, String userEmail, String dest, String hotel, String status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(bookingId).build());

        if (user != null) item.put("user_id", AttributeValue.builder().s(user).build());
        if (userEmail != null) item.put("user_email", AttributeValue.builder().s(userEmail).build());
        if (dest != null) item.put("destination", AttributeValue.builder().s(dest).build());
        if (hotel != null) item.put("hotel_number", AttributeValue.builder().s(hotel).build());
        item.put("status", AttributeValue.builder().s(status).build());

        Put hotelPut = Put.builder()
                .tableName(tableName)
                .item(item)
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("userId", user);
        if (userEmail != null) outboxPayload.put("userEmail", userEmail);
        outboxPayload.put("destination", dest);
        outboxPayload.put("hotelNumber", hotel);
        outboxPayload.put("status", status);
        outboxPayload.put("eventType", "HOTEL_BOOKED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "HOTEL_BOOKED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(hotelPut).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }

    private void updateStatusToCancelledAndOutbox(String bookingId, String hotelNumber) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("booking_id", AttributeValue.builder().s(bookingId).build());

        Update hotelUpdate = Update.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #s = :newStatus")
                .expressionAttributeNames(Collections.singletonMap("#s", "status"))
                .expressionAttributeValues(Collections.singletonMap(":newStatus", AttributeValue.builder().s("CANCELLED").build()))
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("hotelNumber", hotelNumber);
        outboxPayload.put("status", "CANCELLED");
        outboxPayload.put("eventType", "HOTEL_CANCELLED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "HOTEL_CANCELLED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().update(hotelUpdate).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }
}