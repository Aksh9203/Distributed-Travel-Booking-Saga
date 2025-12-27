package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class BookingRequest{
    String bookingId;
    String userId;
    String destination;

    public BookingRequest(){}
}

class BookingResponse{
    String bookingId;
    String status;
    String message;
    Integer amount;

    public BookingResponse(String bookingId, String status, String message, Integer amount) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.amount = amount;
    }
}

public class PaymentHandler implements RequestHandler<Map<String,Object>,BookingResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Payments";

    public PaymentHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .Region(Region.US_EAST_1)
                .build();
    }

    @Override
    public BookingResponse handleRequest(Map<String,Object> input, Context context) {

        String bookingId = (String) input.getOrDefault("bookingId",UUID.randomUUID().toString());
        String userId  = (String) input.get("userId");
        Ineteger amount = (Integer) imput.getOrDefault("amount",150);

        context.getLogger().log("Payent Processeing for: " + userId);

        if(amount>1000){
            throw new RuntimeException("Amount must be less than 1000");
        }

        String transcationId = "TX-" + UUID.randomUUID().toString();

        context.getLogger().log("Payment processed successfully. Transaction ID is: " + transcationId);

        savingBooking(bookingId, userId, destination, amount);

        return new BookingResponse(bookingId,"SUCCESS","PAYMENT PROCESSED",amount);

    }

    private void savingBooking(String id, String user, String dest, Int amt){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());
        item.put("user_Id", AttributeValue.builder().s(user).build());
        item.put("destination", AttributeValue.builder().s(dest).build());
        item.put("amount", AttributeValue.builder().n(String.valueOf(amt)).build());
        item.put("status", AttributeValue.builder().s("PROCESSED").build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}