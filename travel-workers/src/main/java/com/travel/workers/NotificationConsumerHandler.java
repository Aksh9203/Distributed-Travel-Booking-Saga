package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * Asynchronous Notification Consumer Lambda that polls SQS messages,
 * parses Outbox event payloads (unwrapping SNS envelopes if needed),
 * and triggers AWS SES to dispatch transactional confirmation emails to users.
 */
public class NotificationConsumerHandler implements RequestHandler<SQSEvent, String> {

    private final SesClient sesClient;
    private final String fromEmail;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public NotificationConsumerHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.sesClient = SesClient.builder()
                .region(Region.of(regionStr))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        this.fromEmail = System.getenv().getOrDefault("SES_FROM_EMAIL", "noreply@travelbooking.com");
    }

    public NotificationConsumerHandler(SesClient sesClient, String fromEmail) {
        this.sesClient = sesClient;
        this.fromEmail = fromEmail;
    }

    @Override
    public String handleRequest(SQSEvent sqsEvent, Context context) {
        if (sqsEvent == null || sqsEvent.getRecords() == null || sqsEvent.getRecords().isEmpty()) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log("No records received in SQS event.");
            }
            return "SUCCESS: 0 messages processed";
        }

        int sentCount = 0;

        for (SQSMessage message : sqsEvent.getRecords()) {
            try {
                JsonNode payloadNode = extractPayload(message.getBody());

                String bookingId = getField(payloadNode, "bookingId");
                String eventType = getField(payloadNode, "eventType");
                String userEmail = getField(payloadNode, "userEmail");
                if (userEmail == null) {
                    userEmail = getField(payloadNode, "email");
                }
                String destination = getField(payloadNode, "destination");
                String status = getField(payloadNode, "status");

                if (userEmail == null || userEmail.trim().isEmpty()) {
                    if (context != null && context.getLogger() != null) {
                        context.getLogger().log("Skipping record without recipient email for booking: " + bookingId);
                    }
                    continue;
                }

                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Sending SES notification to " + userEmail +
                            " for bookingId: " + bookingId + " (" + eventType + ")");
                }

                sendEmail(userEmail, bookingId, eventType, destination, status, payloadNode, context);
                sentCount++;

            } catch (Exception e) {
                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Error processing SQS record " + message.getMessageId() + ": " + e.getMessage());
                }
                // Throwing exception lets SQS retry and eventually route to Dead Letter Queue (DLQ) after max retries
                throw new RuntimeException("Failed to process SQS notification for message " + message.getMessageId(), e);
            }
        }

        return "SUCCESS: " + sentCount + " emails sent";
    }

    /**
     * Extracts payload JSON, automatically unwrapping the SNS JSON envelope if SQS subscribed without RawMessageDelivery.
     */
    private JsonNode extractPayload(String body) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(body);

        if (root.has("Type") && "Notification".equalsIgnoreCase(root.get("Type").asText()) && root.has("Message")) {
            String innerMessage = root.get("Message").asText();
            return OBJECT_MAPPER.readTree(innerMessage);
        }

        return root;
    }

    private void sendEmail(String toEmail, String bookingId, String eventType, String destination,
                           String status, JsonNode payloadNode, Context context) {
        String subject = "Travel Booking Update: " + (eventType != null ? eventType : "STATUS") + " [" + bookingId + "]";

        StringBuilder htmlBody = new StringBuilder();
        htmlBody.append("<html><body style='font-family: Arial, sans-serif;'>");
        htmlBody.append("<h2 style='color: #2b6cb0;'>Travel Booking Update</h2>");
        htmlBody.append("<p>Hello,</p>");
        htmlBody.append("<p>Here is the latest update on your travel booking:</p>");
        htmlBody.append("<ul>");
        htmlBody.append("<li><strong>Booking ID:</strong> ").append(bookingId).append("</li>");
        if (eventType != null) {
            htmlBody.append("<li><strong>Event:</strong> ").append(eventType).append("</li>");
        }
        if (status != null) {
            htmlBody.append("<li><strong>Status:</strong> ").append(status).append("</li>");
        }
        if (destination != null) {
            htmlBody.append("<li><strong>Destination:</strong> ").append(destination).append("</li>");
        }
        if (payloadNode.has("flightNumber")) {
            htmlBody.append("<li><strong>Flight Number:</strong> ").append(payloadNode.get("flightNumber").asText()).append("</li>");
        }
        if (payloadNode.has("hotelNumber")) {
            htmlBody.append("<li><strong>Hotel Reference:</strong> ").append(payloadNode.get("hotelNumber").asText()).append("</li>");
        }
        if (payloadNode.has("amount")) {
            htmlBody.append("<li><strong>Amount:</strong> $").append(payloadNode.get("amount").asText()).append("</li>");
        }
        htmlBody.append("</ul>");
        htmlBody.append("<p>Thank you for choosing our travel services!</p>");
        htmlBody.append("</body></html>");

        String textBody = "Travel Booking Update\n" +
                "Booking ID: " + bookingId + "\n" +
                "Event: " + eventType + "\n" +
                "Status: " + status + "\n" +
                "Thank you for choosing our travel services!";

        SendEmailRequest emailRequest = SendEmailRequest.builder()
                .source(fromEmail)
                .destination(Destination.builder().toAddresses(toEmail).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .html(Content.builder().data(htmlBody.toString()).charset("UTF-8").build())
                                .text(Content.builder().data(textBody).charset("UTF-8").build())
                                .build())
                        .build())
                .build();

        SendEmailResponse response = sesClient.sendEmail(emailRequest);
        if (context != null && context.getLogger() != null) {
            context.getLogger().log("SES Email successfully sent. MessageId: " + response.messageId());
        }
    }

    private String getField(JsonNode node, String fieldName) {
        if (node != null && node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText();
        }
        return null;
    }
}
