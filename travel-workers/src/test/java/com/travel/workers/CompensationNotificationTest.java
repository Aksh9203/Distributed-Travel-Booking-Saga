package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A customer whose booking rolls back must be emailed, given the exact Parameters the ASL compensation states produce. */
public class CompensationNotificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Execution state as it reaches "Undo Flight" after "Book Hotel" failed.
    private static final String EXECUTION_STATE = "{"
            + "\"userId\":\"u-1\",\"userEmail\":\"customer@example.com\",\"bookingId\":\"bk-rollback\","
            + "\"destination\":\"Atlantis\",\"amount\":100,"
            + "\"paymentResult\":{\"transactionId\":\"TX-1\"},"
            + "\"flightResult\":{\"flightNumber\":\"FL-1\"},"
            + "\"errorInfo\":{\"Error\":\"RuntimeException\"}}";

    private DynamoDbClient dynamoDb;
    private SesClient ses;
    private Context context;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        ses = mock(SesClient.class);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Undo Flight", "Refund Payment"})
    void compensationStateEmailsCustomer(String stateName) throws Exception {
        JsonNode state = MAPPER.readTree(Path.of("../infrastructure/step-function.json.example").toFile())
                .path("States").path(stateName);
        Map<String, Object> input = resolveParameters(state.path("Parameters"), MAPPER.readTree(EXECUTION_STATE));

        if (state.path("Resource").asText().endsWith("TravelFlightFunction")) {
            new FlightHandler(dynamoDb).handleRequest(input, context);
        } else {
            new PaymentHandler(dynamoDb).handleRequest(input, context);
        }

        assertCustomerEmailedOnce(outboxPayload());
    }

    @Test
    void hotelCancelEmailsCustomer() throws Exception {
        new HotelHandler(dynamoDb).handleRequest(Map.of("action", "CANCEL", "bookingId", "bk-rollback",
                "hotelNumber", "HL-1", "userEmail", "customer@example.com"), context);

        assertCustomerEmailedOnce(outboxPayload());
    }

    @Test
    void compensationPayloadsOmitAbsentUserEmail() throws Exception {
        new FlightHandler(dynamoDb).handleRequest(Map.of("action", "CANCEL", "bookingId", "b", "flightNumber", "FL-1"), context);
        new HotelHandler(dynamoDb).handleRequest(Map.of("action", "CANCEL", "bookingId", "b", "hotelNumber", "HL-1"), context);
        new PaymentHandler(dynamoDb).handleRequest(Map.of("action", "REFUND", "bookingId", "b", "transactionId", "TX-1", "amount", 100), context);

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(3)).transactWriteItems(captor.capture());
        for (TransactWriteItemsRequest request : captor.getAllValues()) {
            assertFalse(MAPPER.readTree(request.transactItems().get(1).put().item().get("payload").s()).has("userEmail"));
        }
    }

    private String outboxPayload() {
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        return captor.getValue().transactItems().get(1).put().item().get("payload").s();
    }

    private void assertCustomerEmailedOnce(String payload) throws Exception {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("msg-1");
        message.setBody(MAPPER.writeValueAsString(Map.of("Type", "Notification", "Message", payload)));
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));

        new NotificationConsumerHandler(ses, "noreply@travelbooking.com").handleRequest(event, context);

        ArgumentCaptor<SendEmailRequest> email = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses, times(1)).sendEmail(email.capture());
        assertEquals(List.of("customer@example.com"), email.getValue().destination().toAddresses());
    }

    /** Resolves "key.$": "$.a.b" entries the way Step Functions does; a missing path fails like States.Runtime. */
    private static Map<String, Object> resolveParameters(JsonNode parameters, JsonNode executionState) {
        ObjectNode resolved = MAPPER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = parameters.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (field.getKey().endsWith(".$")) {
                String pointer = field.getValue().asText().substring(1).replace('.', '/');
                JsonNode value = executionState.at(pointer);
                assertFalse(value.isMissingNode(), "path not found in execution state: " + field.getValue().asText());
                resolved.set(field.getKey().substring(0, field.getKey().length() - 2), value);
            } else {
                resolved.set(field.getKey(), field.getValue());
            }
        }
        return MAPPER.convertValue(resolved, Map.class);
    }
}
