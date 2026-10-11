package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OutboxRelayHandlerTest {

    private SnsClient snsClient;
    private Context context;
    private LambdaLogger logger;
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:TravelBookingEvents";

    @BeforeEach
    void setUp() {
        snsClient = mock(SnsClient.class);
        context = mock(Context.class);
        logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    void testRelayValidInsertRecord() {
        OutboxRelayHandler handler = new OutboxRelayHandler(snsClient, TOPIC_ARN);

        Map<String, AttributeValue> newImage = new HashMap<>();
        newImage.put("eventId", new AttributeValue().withS("ev-outbox-1"));
        newImage.put("bookingId", new AttributeValue().withS("bk-test-100"));
        newImage.put("eventType", new AttributeValue().withS("PAYMENT_COMPLETED"));
        newImage.put("payload", new AttributeValue().withS("{\"amount\":250,\"userId\":\"u-1\"}"));

        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(newImage);

        DynamodbStreamRecord record = new DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(Collections.singletonList(record));

        when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("msg-sns-1").build());

        String result = handler.handleRequest(event, context);

        assertTrue(result.contains("1 records processed"));

        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient, times(1)).publish(captor.capture());

        PublishRequest request = captor.getValue();
        assertEquals(TOPIC_ARN, request.topicArn());
        assertEquals("{\"amount\":250,\"userId\":\"u-1\"}", request.message());
        assertEquals("PAYMENT_COMPLETED", request.messageAttributes().get("eventType").stringValue());
        assertEquals("bk-test-100", request.messageAttributes().get("bookingId").stringValue());
        assertEquals("ev-outbox-1", request.messageAttributes().get("eventId").stringValue());
    }

    @Test
    void testSkipNonInsertRecord() {
        OutboxRelayHandler handler = new OutboxRelayHandler(snsClient, TOPIC_ARN);

        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(Collections.emptyMap());

        DynamodbStreamRecord record = new DynamodbStreamRecord();
        record.setEventName("MODIFY");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(Collections.singletonList(record));

        String result = handler.handleRequest(event, context);

        assertTrue(result.contains("0 records processed"));
        verify(snsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void testSnsPublishFailureThrowsException() {
        OutboxRelayHandler handler = new OutboxRelayHandler(snsClient, TOPIC_ARN);

        Map<String, AttributeValue> newImage = new HashMap<>();
        newImage.put("eventId", new AttributeValue().withS("ev-err-1"));
        newImage.put("bookingId", new AttributeValue().withS("bk-err-1"));
        newImage.put("eventType", new AttributeValue().withS("PAYMENT_COMPLETED"));
        newImage.put("payload", new AttributeValue().withS("{\"error\":true}"));

        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(newImage);

        DynamodbStreamRecord record = new DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(Collections.singletonList(record));

        when(snsClient.publish(any(PublishRequest.class)))
                .thenThrow(new RuntimeException("SNS network error"));

        assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
    }
}
