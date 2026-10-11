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

class PaymentResponse {
    public String bookingId;
    public String status;
    public String message;
    public String transactionId;
    public Integer amount;

    public PaymentResponse(String bookingId, String status, String message, String transactionId, Integer amount) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.transactionId = transactionId;
        this.amount = amount;
    }
}

public class PaymentHandler implements RequestHandler<Map<String, Object>, PaymentResponse> {

    private final DynamoDbClient dynamoDb;
    private final String tableName = System.getenv().getOrDefault("PAYMENT_TABLE_NAME", "Travel_Payments");

    public PaymentHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.of(regionStr))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public PaymentHandler(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public PaymentResponse handleRequest(Map<String, Object> input, Context context) {
        String action = (String) input.getOrDefault("action", "PAY");

        if ("REFUND".equalsIgnoreCase(action)) {
            return paymentRefunded(input, context);
        } else {
            return paymentSuccessful(input, context);
        }
    }

    private PaymentResponse paymentSuccessful(Map<String, Object> input, Context context) {
        String bookingId = (String) input.getOrDefault("bookingId", UUID.randomUUID().toString());
        String userId = (String) input.get("userId");
        String userEmail = (String) input.getOrDefault("userEmail", input.get("email"));
        Integer amount = (Integer) input.getOrDefault("amount", 150);

        context.getLogger().log("Payment processing for: " + userId + ", bookingId: " + bookingId);

        if (amount > 1000) {
            throw new RuntimeException("Amount must be less than 1000");
        }

        String transactionId = "TX-" + UUID.randomUUID().toString();
        context.getLogger().log("Payment processed successfully. Transaction ID: " + transactionId);

        savePaymentAndOutbox(bookingId, userId, userEmail, transactionId, amount, "PAYMENT PROCESSED");
        return new PaymentResponse(bookingId, "SUCCESS", "PAYMENT PROCESSED", transactionId, amount);
    }

    private PaymentResponse paymentRefunded(Map<String, Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");
        String transactionId = (String) input.get("transactionId");
        Integer amount = (Integer) input.getOrDefault("amount", 0);

        context.getLogger().log("Compensating Transaction: Refunding Payment for Booking ID: " + bookingId);

        updateStatusToRefundedAndOutbox(bookingId, transactionId, amount);
        return new PaymentResponse(bookingId, "SUCCESS", "PAYMENT REFUNDED", transactionId, amount);
    }

    private void savePaymentAndOutbox(String bookingId, String userId, String userEmail, String txId, Integer amt, String status) {
        Map<String, AttributeValue> paymentItem = new HashMap<>();
        paymentItem.put("booking_id", AttributeValue.builder().s(bookingId).build());

        if (userId != null) paymentItem.put("user_id", AttributeValue.builder().s(userId).build());
        if (userEmail != null) paymentItem.put("user_email", AttributeValue.builder().s(userEmail).build());
        if (txId != null) paymentItem.put("transaction_id", AttributeValue.builder().s(txId).build());
        paymentItem.put("amount", AttributeValue.builder().n(String.valueOf(amt)).build());
        paymentItem.put("status", AttributeValue.builder().s(status).build());

        Put paymentPut = Put.builder()
                .tableName(tableName)
                .item(paymentItem)
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("userId", userId);
        if (userEmail != null) outboxPayload.put("userEmail", userEmail);
        outboxPayload.put("transactionId", txId);
        outboxPayload.put("amount", amt);
        outboxPayload.put("status", status);
        outboxPayload.put("eventType", "PAYMENT_COMPLETED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "PAYMENT_COMPLETED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(paymentPut).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }

    private void updateStatusToRefundedAndOutbox(String bookingId, String transactionId, Integer amount) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("booking_id", AttributeValue.builder().s(bookingId).build());

        Update paymentUpdate = Update.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #s = :newStatus")
                .expressionAttributeNames(Collections.singletonMap("#s", "status"))
                .expressionAttributeValues(Collections.singletonMap(":newStatus", AttributeValue.builder().s("REFUNDED").build()))
                .build();

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("bookingId", bookingId);
        outboxPayload.put("transactionId", transactionId);
        outboxPayload.put("amount", amount);
        outboxPayload.put("status", "REFUNDED");
        outboxPayload.put("eventType", "PAYMENT_REFUNDED");

        TransactWriteItem outboxItem = OutboxHelper.buildOutboxTransactItem(
                bookingId,
                "PAYMENT_REFUNDED",
                outboxPayload
        );

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().update(paymentUpdate).build(),
                        outboxItem
                )
                .build();

        dynamoDb.transactWriteItems(request);
    }
}