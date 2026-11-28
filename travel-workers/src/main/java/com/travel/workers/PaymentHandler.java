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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class PaymentResponse {
    public String bookingId;
    public String status;
    public String message;
    public String transactionId;
    public BigDecimal amount;

    public PaymentResponse(String bookingId, String status, String message, String transactionId, BigDecimal amount) {
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
        }
        if ("PAY".equalsIgnoreCase(action)) {
            return paymentSuccessful(input, context);
        }
        throw new IllegalArgumentException("Unknown payment action: " + action);
    }

    private PaymentResponse paymentSuccessful(Map<String, Object> input, Context context) {
        String bookingId = (String) input.getOrDefault("bookingId", UUID.randomUUID().toString());
        String userId = (String) input.get("userId");
        String userEmail = (String) input.getOrDefault("userEmail", input.get("email"));
        // Any JSON number (travel-api sends a Double); toString keeps 100 as 100 and 100.5 exact.
        BigDecimal amount = new BigDecimal(input.getOrDefault("amount", 150).toString());

        context.getLogger().log("Payment processing for: " + userId + ", bookingId: " + bookingId);

        if (amount.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new RuntimeException("Amount must not exceed 1000");
        }

        String transactionId = "TX-" + UUID.randomUUID().toString();
        context.getLogger().log("Payment processed successfully. Transaction ID: " + transactionId);

        savePaymentAndOutbox(bookingId, userId, userEmail, transactionId, amount, "PAYMENT PROCESSED");
        return new PaymentResponse(bookingId, "SUCCESS", "PAYMENT PROCESSED", transactionId, amount);
    }

    private PaymentResponse paymentRefunded(Map<String, Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");
        String transactionId = (String) input.get("transactionId");
        BigDecimal amount = new BigDecimal(input.getOrDefault("amount", 0).toString());
        String userEmail = (String) input.getOrDefault("userEmail", input.get("email"));

        context.getLogger().log("Compensating Transaction: Refunding Payment for Booking ID: " + bookingId);

        updateStatusToRefundedAndOutbox(bookingId, transactionId, amount, userEmail);
        return new PaymentResponse(bookingId, "SUCCESS", "PAYMENT REFUNDED", transactionId, amount);
    }

    private void savePaymentAndOutbox(String bookingId, String userId, String userEmail, String txId, BigDecimal amt, String status) {
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

    private void updateStatusToRefundedAndOutbox(String bookingId, String transactionId, BigDecimal amount, String userEmail) {
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
        if (userEmail != null) outboxPayload.put("userEmail", userEmail);
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