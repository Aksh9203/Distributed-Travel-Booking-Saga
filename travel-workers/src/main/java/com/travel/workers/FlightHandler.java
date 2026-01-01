package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.util.Collections;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


//Output
class FlightResponse{
    public String bookingId;
    public String status;
    public String message;
    public String flightNumber;

    public FlightResponse(String bookingId, String status, String message, String flightNumber){
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.flightNumber = flightNumber;
    }
}

//Handler(actual lambda code)
public class FlightHandler implements RequestHandler<Map<String,Object>,FlightResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Flights";

    public FlightHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public FlightResponse handleRequest(Map<String,Object> input, Context context) {
       String action = (String)input.getOrDefault("action","BOOK");
       
       if("CANCEL".equalsIgnoreCase(action)){
           return cancelFlight(input,context);
       }
       else{
           return bookFlight(input,context);
       }

    }
    
    private FlightResponse bookFlight(Map<String,Object> input, Context context){
        //Extract data from input
        String bookingId = (String)input.getOrDefault("bookingId",UUID.randomUUID().toString());
        String userId = (String)input.get("userId");
        String destination = (String)input.get("destination");

        context.getLogger().log("Booking Flights to: " + destination);

        //Failure Scenario
        if("Mars".equalsIgnoreCase(destination)){
            throw new RuntimeException("Flights to Mars are temporarily cancel worldwide. Try Later.");
        }

        String flightNumber = "FL-" + (int)(Math.random()*1000);

        //Saving result to DynamoDb
        saveBookingStatus(bookingId, userId, destination, flightNumber,"CONFIRMED");

        context.getLogger().log("Flight Confirmed: " + flightNumber);
        return new FlightResponse(bookingId, "SUCCESS", "Flight Booked", flightNumber);
    }
    
    private FlightResponse cancelFlight(Map<String,Object> input, Context context){
        String bookingId = (String)input.get("bookingId");
        String flightNumber = (String)input.get("flightNumber");
        
        context.getLogger().log("Compensating Transaction: Cancelling Flight for Booking ID: " + bookingId);

        updateStatusToCancelled(bookingId);
        return new FlightResponse(bookingId, "SUCCESS", "Flight Cancelled (Compensated)", flightNumber);
    }

    private void updateStatusToCancelled(String id){
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("booking_id",AttributeValue.builder().s(id).build());

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                // use "#s" because 'status' is a reserved word in DynamoDB
                .updateExpression("SET #s = :newStatus")
                .expressionAttributeNames(Collections.singletonMap("#s", "status"))
                .expressionAttributeValues(Collections.singletonMap(":newStatus", AttributeValue.builder().s("CANCELLED").build()))
                .build();

        dynamoDb.updateItem(request);
    }

    private void saveBookingStatus(String id, String user, String dest, String flight, String status){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());
        
        //To avoid null data problem of DynamoDb
        if(user != null) item.put("user_id", AttributeValue.builder().s(user).build());
        if(dest != null) item.put("destination", AttributeValue.builder().s(dest).build());
        if(flight != null) item.put("flight_number", AttributeValue.builder().s(flight).build());
        
        item.put("status", AttributeValue.builder().s(status).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}
