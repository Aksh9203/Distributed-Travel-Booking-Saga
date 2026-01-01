package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import java.util.Collections;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class PaymentResponse{
    public String bookingId;
    public String status;
    public String message;
    public String transactionId;
    public Integer amount;

    public PaymentResponse(String bookingId, String status, String message, String transactionId, Integer amount) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.transactionId = transactionId;
        this.amount = amount;
    }
}

public class PaymentHandler implements RequestHandler<Map<String,Object>,PaymentResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Payments";

    public PaymentHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public PaymentResponse handleRequest(Map<String,Object> input, Context context) {
        String action = (String)input.getOrDefault("action","PAY");

        if("REFUND".equalsIgnoreCase(action)){
            return paymentRefunded(input,context);
        }
        else{
            return paymentSuccessfull(input,context);
        }
    }
    
    private PaymentResponse paymentSuccessfull(Map<String,Object> input, Context context){
        //Extract data from the input.
        String bookingId = (String) input.getOrDefault("bookingId",UUID.randomUUID().toString());
        String userId  = (String) input.get("userId");
        Integer amount = (Integer) input.getOrDefault("amount",150);

        context.getLogger().log("Payent Processeing for: " + userId);

        if(amount>1000){
            throw new RuntimeException("Amount must be less than 1000");
        }

        String transcationId = "TX-" + UUID.randomUUID().toString();

        context.getLogger().log("Payment processed successfully. Transaction ID is: " + transcationId);

        saveBookingStatus(bookingId, userId, transcationId, amount, "PAYMENT PROCESSED");
        return new PaymentResponse(bookingId,"SUCCESS","PAYMENT PROCESSED",transcationId,amount);
    }

    private PaymentResponse paymentRefunded(Map<String,Object> input, Context context){
        String bookingId = (String) input.get("bookingId");
        String transactionId = (String) input.get("transactionId");
        Integer amount = (Integer) input.getOrDefault("amount",0);

        context.getLogger().log("Compensating Transaction: Refunding Payment for Booking ID: " + bookingId);

        updateStatusToRefunded(bookingId);
        return new PaymentResponse(bookingId,"SUCCESS","PAYMENT REFUNDED",transactionId,amount);
    }

    private void updateStatusToRefunded(String id){
        Map<String,AttributeValue> key = new HashMap<>();
        key.put("booking_id", AttributeValue.builder().s(id).build());

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                // use "#s" because 'status' is a reserved word in DynamoDB
                .updateExpression("SET #s = :newStatus")
                .expressionAttributeNames(Collections.singletonMap("#s", "status"))
                .expressionAttributeValues(Collections.singletonMap(":newStatus",AttributeValue.builder().s("REFUNDED").build()))
                .build();

        dynamoDb.updateItem(request);
    }
    

    private void saveBookingStatus(String id, String user, String txId, Integer amt, String status){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());


        if(user != null) item.put("user_id", AttributeValue.builder().s(user).build());
        if(txId != null) item.put("transaction_id", AttributeValue.builder().s(txId).build());

        item.put("amount", AttributeValue.builder().n(String.valueOf(amt)).build());
        item.put("status", AttributeValue.builder().s(status).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}