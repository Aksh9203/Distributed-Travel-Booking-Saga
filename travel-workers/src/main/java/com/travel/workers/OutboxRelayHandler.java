package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Background Relay Lambda function that processes DynamoDB Streams from the TravelOutbox table.
 * It parses NEW_IMAGE records and publishes event payloads to the central SNS Topic,
 * ensuring reliable at-least-once asynchronous event delivery.
 */
public class OutboxRelayHandler implements RequestHandler<DynamodbEvent, String> {

    private final SnsClient snsClient;
    private final String topicArn;

    public OutboxRelayHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.snsClient = SnsClient.builder()
                .region(Region.of(regionStr))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        this.topicArn = System.getenv().getOrDefault("SNS_TOPIC_ARN",
                System.getenv().getOrDefault("OUTBOX_TOPIC_ARN", "arn:aws:sns:us-east-1:123456789012:TravelBookingEvents"));
    }

    public OutboxRelayHandler(SnsClient snsClient, String topicArn) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
    }

    @Override
    public String handleRequest(DynamodbEvent dynamodbEvent, Context context) {
        if (dynamodbEvent == null || dynamodbEvent.getRecords() == null || dynamodbEvent.getRecords().isEmpty()) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log("No records received in DynamoDB Stream event.");
            }
            return "SUCCESS: 0 records processed";
        }

        int processedCount = 0;

        for (DynamodbStreamRecord record : dynamodbEvent.getRecords()) {
            String eventName = record.getEventName();

            // Only relay newly inserted Outbox entries
            if (!"INSERT".equalsIgnoreCase(eventName)) {
                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Skipping non-INSERT event: " + eventName);
                }
                continue;
            }

            if (record.getDynamodb() == null || record.getDynamodb().getNewImage() == null) {
                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Skipping record with missing NewImage.");
                }
                continue;
            }

            Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();

            String eventId = getStringAttribute(newImage, "eventId");
            String bookingId = getStringAttribute(newImage, "bookingId");
            String eventType = getStringAttribute(newImage, "eventType");
            String payload = getStringAttribute(newImage, "payload");

            if (payload == null || eventType == null) {
                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Record missing required payload or eventType. EventId: " + eventId);
                }
                continue;
            }

            if (context != null && context.getLogger() != null) {
                context.getLogger().log("Relaying Outbox event: " + eventType + " for booking: " + bookingId + ", eventId: " + eventId);
            }

            publishToSns(eventId, bookingId, eventType, payload, context);
            processedCount++;
        }

        return "SUCCESS: " + processedCount + " records processed";
    }

    private void publishToSns(String eventId, String bookingId, String eventType, String payload, Context context) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

        if (eventType != null) {
            messageAttributes.put("eventType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(eventType)
                    .build());
        }

        if (bookingId != null) {
            messageAttributes.put("bookingId", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(bookingId)
                    .build());
        }

        if (eventId != null) {
            messageAttributes.put("eventId", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(eventId)
                    .build());
        }

        PublishRequest publishRequest = PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .subject("TravelBookingEvent:" + eventType)
                .messageAttributes(messageAttributes)
                .build();

        try {
            PublishResponse response = snsClient.publish(publishRequest);
            if (context != null && context.getLogger() != null) {
                context.getLogger().log("Successfully published to SNS. MessageId: " + response.messageId());
            }
        } catch (Exception e) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log("Error publishing Outbox event to SNS: " + e.getMessage());
            }
            throw new RuntimeException("Failed to relay Outbox event to SNS for eventId: " + eventId, e);
        }
    }

    private String getStringAttribute(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return val != null ? val.getS() : null;
    }
}
