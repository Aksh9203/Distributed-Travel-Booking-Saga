package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class NotificationConsumerHandlerTest {

    private SesClient sesClient;
    private Context context;
    private LambdaLogger logger;
    private static final String FROM_EMAIL = "notifications@travelservice.com";

    @BeforeEach
    void setUp() {
        sesClient = mock(SesClient.class);
        context = mock(Context.class);
        logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    void testDirectPayloadDispatchesEmail() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);

        String jsonPayload = "{" +
                "\"bookingId\":\"bk-direct-1\"," +
                "\"userEmail\":\"traveler@domain.com\"," +
                "\"eventType\":\"HOTEL_BOOKED\"," +
                "\"destination\":\"Rome\"," +
                "\"amount\":450" +
                "}";

        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setMessageId("sqs-msg-1");
        sqsMessage.setBody(jsonPayload);

        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.singletonList(sqsMessage));

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-out-1").build());

        SQSBatchResponse result = handler.handleRequest(event, context);

        assertTrue(result.getBatchItemFailures().isEmpty());

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient, times(1)).sendEmail(captor.capture());

        SendEmailRequest request = captor.getValue();
        assertEquals(FROM_EMAIL, request.source());
        assertTrue(request.destination().toAddresses().contains("traveler@domain.com"));
        assertTrue(request.message().subject().data().contains("bk-direct-1"));
        assertTrue(request.message().body().html().data().contains("Rome"));
        assertTrue(request.message().body().html().data().contains("$450"));
    }

    @Test
    void testSnsWrappedEnvelopeExtractsAndSendsEmail() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);

        String innerMessage = "{\\\"bookingId\\\":\\\"bk-sns-wrap\\\",\\\"userEmail\\\":\\\"passenger@flight.com\\\",\\\"eventType\\\":\\\"FLIGHT_BOOKED\\\",\\\"flightNumber\\\":\\\"FL-999\\\"}";
        String wrappedBody = "{" +
                "\"Type\":\"Notification\"," +
                "\"MessageId\":\"sns-uuid-1\"," +
                "\"TopicArn\":\"arn:aws:sns:us-east-1:123456789012:TravelBookingEvents\"," +
                "\"Message\":\"" + innerMessage + "\"" +
                "}";

        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setMessageId("sqs-msg-2");
        sqsMessage.setBody(wrappedBody);

        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.singletonList(sqsMessage));

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-out-2").build());

        SQSBatchResponse result = handler.handleRequest(event, context);

        assertTrue(result.getBatchItemFailures().isEmpty());

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient, times(1)).sendEmail(captor.capture());

        SendEmailRequest request = captor.getValue();
        assertTrue(request.destination().toAddresses().contains("passenger@flight.com"));
        assertTrue(request.message().body().html().data().contains("FL-999"));
    }

    @Test
    void testHtmlBodyEscapesPayloadFields() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);

        String jsonPayload = "{" +
                "\"bookingId\":\"bk-xss\"," +
                "\"userEmail\":\"traveler@domain.com\"," +
                "\"destination\":\"<img src=x onerror=alert(1)>\"," +
                "\"flightNumber\":\"<img src=y>\"" +
                "}";

        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setMessageId("sqs-msg-xss");
        sqsMessage.setBody(jsonPayload);

        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.singletonList(sqsMessage));

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-out-xss").build());

        handler.handleRequest(event, context);

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());

        String html = captor.getValue().message().body().html().data();
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
        assertTrue(html.contains("&lt;img src=y&gt;"), html);
    }

    @Test
    void testMissingEmailSkipsSend() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);

        String jsonPayload = "{\"bookingId\":\"bk-no-email\",\"eventType\":\"PAYMENT_COMPLETED\"}";

        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setMessageId("sqs-msg-3");
        sqsMessage.setBody(jsonPayload);

        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.singletonList(sqsMessage));

        SQSBatchResponse result = handler.handleRequest(event, context);

        assertTrue(result.getBatchItemFailures().isEmpty());
        verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSesFailureIsReportedAsBatchItemFailure() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);

        String jsonPayload = "{\"bookingId\":\"bk-err\",\"userEmail\":\"err@domain.com\",\"eventType\":\"PAYMENT_COMPLETED\"}";

        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setMessageId("sqs-msg-4");
        sqsMessage.setBody(jsonPayload);

        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.singletonList(sqsMessage));

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(new RuntimeException("SES quota exceeded"));

        SQSBatchResponse result = handler.handleRequest(event, context);

        assertEquals(1, result.getBatchItemFailures().size());
        assertEquals("sqs-msg-4", result.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void batchResponseSerializesToLambdaShape() throws Exception {
        SQSBatchResponse response = new SQSBatchResponse(
                List.of(new SQSBatchResponse.BatchItemFailure("sqs-msg-4")));

        assertEquals("{\"batchItemFailures\":[{\"itemIdentifier\":\"sqs-msg-4\"}]}",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response));
    }

    private static SQSMessage msg(String id, String body) {
        SQSMessage m = new SQSMessage();
        m.setMessageId(id);
        m.setBody(body);
        return m;
    }

    private long emailsTo(String addr) {
        ArgumentCaptor<SendEmailRequest> c = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient, atLeast(0)).sendEmail(c.capture());
        return c.getAllValues().stream().filter(r -> r.destination().toAddresses().contains(addr)).count();
    }

    @Test
    void poisonMessageIsTheOnlyOneRedelivered() {
        NotificationConsumerHandler handler = new NotificationConsumerHandler(sesClient, FROM_EMAIL);
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-out").build());

        List<SQSMessage> inFlight = new ArrayList<>(List.of(
                msg("m1", "{\"bookingId\":\"b1\",\"userEmail\":\"a@x.com\"}"),
                msg("m2", "not-json"),
                msg("m3", "{\"bookingId\":\"b3\",\"userEmail\":\"c@x.com\"}")));
        List<String> lastReported = List.of();

        // Simulate Lambda with maxReceiveCount 3: a throw redelivers the whole batch,
        // a batch response redelivers only the reported messageIds.
        for (int receive = 1; receive <= 3 && !inFlight.isEmpty(); receive++) {
            SQSEvent event = new SQSEvent();
            event.setRecords(inFlight);
            List<String> failed;
            try {
                Object result = handler.handleRequest(event, context);
                failed = result instanceof SQSBatchResponse
                        ? ((SQSBatchResponse) result).getBatchItemFailures().stream()
                                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                                .collect(Collectors.toList())
                        : List.of();
            } catch (RuntimeException e) {
                failed = inFlight.stream().map(SQSMessage::getMessageId).collect(Collectors.toList());
            }
            List<String> retry = failed;
            inFlight.removeIf(m -> !retry.contains(m.getMessageId()));
            lastReported = failed;
        }

        assertEquals(1, emailsTo("a@x.com"), "a@x.com emails after 3 receives");
        assertEquals(1, emailsTo("c@x.com"), "c@x.com emails after 3 receives");
        assertEquals(List.of("m2"), lastReported, "only the poison message is reported failed");
    }
}
