package com.travel.workers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OutboxHelper {

    public static final String OUTBOX_TABLE_NAME =
            System.getenv().getOrDefault("OUTBOX_TABLE_NAME", "TravelOutbox");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_TTL_SECONDS = 7L * 24 * 60 * 60; // 7 days retention

    /**
     * Builds a DynamoDB Put action for the Outbox table according to the schema:
     * eventId (S), bookingId (S), eventType (S), payload (S / JSON), createdAt (N), ttl (N)
     */
    public static Put buildOutboxPut(String bookingId, String eventType, Map<String, Object> payloadData) {
        String eventId = UUID.randomUUID().toString();
        long nowEpochSecond = Instant.now().getEpochSecond();
        long ttlEpochSecond = nowEpochSecond + DEFAULT_TTL_SECONDS;

        String payloadJson;
        try {
            payloadJson = OBJECT_MAPPER.writeValueAsString(payloadData);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("eventId", AttributeValue.builder().s(eventId).build());
        item.put("bookingId", AttributeValue.builder().s(bookingId).build());
        item.put("eventType", AttributeValue.builder().s(eventType).build());
        item.put("payload", AttributeValue.builder().s(payloadJson).build());
        item.put("createdAt", AttributeValue.builder().n(String.valueOf(nowEpochSecond)).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttlEpochSecond)).build());

        return Put.builder()
                .tableName(OUTBOX_TABLE_NAME)
                .item(item)
                .build();
    }

    /**
     * Builds a TransactWriteItem wrapping the Outbox Put action.
     */
    public static TransactWriteItem buildOutboxTransactItem(String bookingId, String eventType, Map<String, Object> payloadData) {
        return TransactWriteItem.builder()
                .put(buildOutboxPut(bookingId, eventType, payloadData))
                .build();
    }
}
