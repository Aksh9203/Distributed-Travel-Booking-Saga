package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WorkersOutboxTest {

    private DynamoDbClient dynamoDb;
    private Context context;
    private LambdaLogger logger;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        context = mock(Context.class);
        logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    void testOutboxHelperCreatesValidItem() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", "b-123");
        payload.put("amount", 250);

        Put outboxPut = OutboxHelper.buildOutboxPut("b-123", "PAYMENT_COMPLETED", payload);

        assertEquals("TravelOutbox", outboxPut.tableName());
        Map<String, AttributeValue> item = outboxPut.item();

        assertNotNull(item.get("eventId").s());
        assertDoesNotThrow(() -> UUID.fromString(item.get("eventId").s()));
        assertEquals("b-123", item.get("bookingId").s());
        assertEquals("PAYMENT_COMPLETED", item.get("eventType").s());
        assertTrue(item.get("payload").s().contains("\"amount\":250"));

        long createdAt = Long.parseLong(item.get("createdAt").n());
        long ttl = Long.parseLong(item.get("ttl").n());
        assertTrue(ttl > createdAt);
        assertEquals(7 * 24 * 60 * 60, ttl - createdAt);
    }

    @Test
    void testPaymentHandlerAtomicWriteSuccess() {
        PaymentHandler handler = new PaymentHandler(dynamoDb);

        Map<String, Object> input = new HashMap<>();
        input.put("bookingId", "bk-pay-1");
        input.put("userId", "user-1");
        input.put("userEmail", "test@example.com");
        input.put("amount", 200);

        PaymentResponse response = handler.handleRequest(input, context);

        assertEquals("SUCCESS", response.status);
        assertEquals("PAYMENT PROCESSED", response.message);

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(1)).transactWriteItems(captor.capture());

        TransactWriteItemsRequest captured = captor.getValue();
        assertEquals(2, captured.transactItems().size());

        // Domain item check
        TransactWriteItem domainItem = captured.transactItems().get(0);
        assertNotNull(domainItem.put());
        assertEquals("Travel_Payments", domainItem.put().tableName());
        assertEquals("bk-pay-1", domainItem.put().item().get("booking_id").s());
        assertEquals("PAYMENT PROCESSED", domainItem.put().item().get("status").s());

        // Outbox item check
        TransactWriteItem outboxItem = captured.transactItems().get(1);
        assertNotNull(outboxItem.put());
        assertEquals("TravelOutbox", outboxItem.put().tableName());
        assertEquals("PAYMENT_COMPLETED", outboxItem.put().item().get("eventType").s());
        assertTrue(outboxItem.put().item().get("payload").s().contains("test@example.com"));
    }

    @Test
    void testPaymentHandlerRefundCompensatingTransaction() {
        PaymentHandler handler = new PaymentHandler(dynamoDb);

        Map<String, Object> input = new HashMap<>();
        input.put("action", "REFUND");
        input.put("bookingId", "bk-pay-1");
        input.put("transactionId", "TX-12345");
        input.put("amount", 200);

        PaymentResponse response = handler.handleRequest(input, context);

        assertEquals("SUCCESS", response.status);
        assertEquals("PAYMENT REFUNDED", response.message);

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(1)).transactWriteItems(captor.capture());

        TransactWriteItemsRequest captured = captor.getValue();
        assertEquals(2, captured.transactItems().size());

        // Update on domain table
        assertNotNull(captured.transactItems().get(0).update());
        assertEquals("Travel_Payments", captured.transactItems().get(0).update().tableName());

        // Put on Outbox
        assertNotNull(captured.transactItems().get(1).put());
        assertEquals("TravelOutbox", captured.transactItems().get(1).put().tableName());
        assertEquals("PAYMENT_REFUNDED", captured.transactItems().get(1).put().item().get("eventType").s());
    }

    @Test
    void testFlightHandlerAtomicWriteSuccess() {
        FlightHandler handler = new FlightHandler(dynamoDb);

        Map<String, Object> input = new HashMap<>();
        input.put("bookingId", "bk-fl-1");
        input.put("userId", "user-1");
        input.put("userEmail", "traveler@example.com");
        input.put("destination", "Tokyo");

        FlightResponse response = handler.handleRequest(input, context);

        assertEquals("SUCCESS", response.status);
        assertTrue(response.flightNumber.startsWith("FL-"));

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(1)).transactWriteItems(captor.capture());

        TransactWriteItemsRequest captured = captor.getValue();
        assertEquals(2, captured.transactItems().size());
        assertEquals("Travel_Flights", captured.transactItems().get(0).put().tableName());
        assertEquals("TravelOutbox", captured.transactItems().get(1).put().tableName());
        assertEquals("FLIGHT_BOOKED", captured.transactItems().get(1).put().item().get("eventType").s());
    }

    @Test
    void testHotelHandlerAtomicWriteSuccess() {
        HotelHandler handler = new HotelHandler(dynamoDb);

        Map<String, Object> input = new HashMap<>();
        input.put("bookingId", "bk-ht-1");
        input.put("userId", "user-1");
        input.put("userEmail", "traveler@example.com");
        input.put("destination", "Paris");

        HotelResponse response = handler.handleRequest(input, context);

        assertEquals("SUCCESS", response.status);
        assertTrue(response.hotelNumber.startsWith("HL-"));

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(1)).transactWriteItems(captor.capture());

        TransactWriteItemsRequest captured = captor.getValue();
        assertEquals(2, captured.transactItems().size());
        assertEquals("Travel_Hotels", captured.transactItems().get(0).put().tableName());
        assertEquals("TravelOutbox", captured.transactItems().get(1).put().tableName());
        assertEquals("HOTEL_BOOKED", captured.transactItems().get(1).put().item().get("eventType").s());
    }
}
