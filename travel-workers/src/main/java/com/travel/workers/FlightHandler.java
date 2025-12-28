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
                .build();
    }

    @Override
    public FlightResponse handleRequest(Map<String,Object> input, Context context) {
        //Extract data from input
        String bookingId = (String)input.getOrDefault("bookingId",UUID.randomUUID().toString());
        String userId = (String)input.get("userId");
        String destination = (String)input.get("destination");

        context.getLogger().log("Booking Flights to: " + destination);

        //Failure Scenario
        if("Mars".equalsIgnoreCase(destination)){
            throw new RuntimeException("Flights to Mars are temprorarily cancel worldwide. Try Later.");
        }

        String flightNumber = "FL-" + (int)(Math.random()*1000);

        //Saving result to DynamoDb
        saveBooking(bookingId, userId, destination, flightNumber);

        context.getLogger().log("Flight Confirmed: " + flightNumber);

        return new FlightResponse(bookingId, "SUCCESS", "Flight Booked", flightNumber);

    }

    private void saveBooking(String id, String user, String dest, String flight){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());
        item.put("user_id", AttributeValue.builder().s(user).build());
        item.put("destination", AttributeValue.builder().s(dest).build());
        item.put("flight_number", AttributeValue.builder().s(flight).build());
        item.put("status", AttributeValue.builder().s("CONFIRMED").build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}
