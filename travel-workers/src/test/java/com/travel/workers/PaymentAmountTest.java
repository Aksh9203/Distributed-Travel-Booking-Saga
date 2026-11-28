package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// travel-api sends amount as a Double, so the Lambda runtime hands PaymentHandler a java.lang.Double.
class PaymentAmountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DynamoDbClient dynamoDb;
    private Context context;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private PaymentResponse run(String body) throws Exception {
        Map<String, Object> input = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        return new PaymentHandler(dynamoDb).handleRequest(input, context);
    }

    private TransactWriteItemsRequest written() {
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        return captor.getValue();
    }

    // The amount token as it appears in the JSON, e.g. "100" or "100.5".
    private static String amountIn(String json) throws Exception {
        return JSON.readTree(json).get("amount").toString();
    }

    private static String amountIn(PaymentResponse response) throws Exception {
        return amountIn(JSON.writeValueAsString(response));
    }

    @Test
    void payKeepsDecimalAmount() throws Exception {
        PaymentResponse response = run("{\"bookingId\":\"bk-1\",\"userId\":\"u1\",\"amount\":100.5}");

        TransactWriteItemsRequest req = written();
        assertEquals("100.5", req.transactItems().get(0).put().item().get("amount").n());
        assertEquals("100.5", amountIn(req.transactItems().get(1).put().item().get("payload").s()));
        assertEquals("100.5", amountIn(response));
    }

    @Test
    void payKeepsIntegerOutputUnchanged() throws Exception {
        PaymentResponse response = run("{\"bookingId\":\"bk-1\",\"userId\":\"u1\",\"amount\":100}");

        TransactWriteItemsRequest req = written();
        assertEquals("100", req.transactItems().get(0).put().item().get("amount").n());
        assertEquals("100", amountIn(req.transactItems().get(1).put().item().get("payload").s()));
        assertEquals("100", amountIn(response));
    }

    @Test
    void payDeclinesDecimalAboveLimit() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> run("{\"bookingId\":\"bk-1\",\"userId\":\"u1\",\"amount\":1000.5}"));
        assertEquals("Amount must not exceed 1000", e.getMessage());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void refundKeepsDecimalAmount() throws Exception {
        PaymentResponse response = run("{\"action\":\"REFUND\",\"bookingId\":\"bk-1\",\"transactionId\":\"TX-1\",\"amount\":100.5}");

        assertEquals("100.5", amountIn(written().transactItems().get(1).put().item().get("payload").s()));
        assertEquals("100.5", amountIn(response));
    }
}
