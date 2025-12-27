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


//Input
class BookingRequest{
    public String bookingId;
    public String userId;
    public String destination;

    public BookingRequest(String bookingId, String userId, String destination){}
}

//Output
class BookingResponse{
    public String bookingId;
    public String status;
    public String message;
    public String flightNumber;

    public BookingResponse(String bookingId, String status, String message, String flightNumber){
        this.bookingId = id;
        this.status = status;
        this.message = msg;
        this.flightNumber = flight;
    }
}

//Handler(actual lambda code)
public class FlightHandler implements RequestHandler<Map<String,Object>,BookingResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Flights";

    public FlightHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build
    }

    @Override
    public BookingResponse handleRequest(Map<String,Object> input, Context context) {
        //Extract data from input
        String bookingId = input.getOrDefault("bookingId",UUID.randomUUID.toString());
        String userId = input.get("userId");
        String destination = input.get("destination");

        context.getLogger.log("Booking Flights to: " + destination);

        //Failure Scenario
        if("Mars".equalsIgnoreCase(destination)){
            throw new RuntimeException("Flights to Mars are temprorarily cancel worldwide. Try Later.")
        }

        String flightNumber = "FL-" + (int)(Math.random()*100);

        //Saving result to DynamoDb
        saveBooking(bookingId, userId, destination, flightNumber);

        context,getlogger.log("Flight Confirmed: " + flightNumber);

        return new BookingResponse(String bookingId, "SUCCESS", "Flight Booked", String flightNumber)

    }

    private void saveBooking(String id, String user, String dest, String flight){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build();
        item.put("user_id", AttributeValue.builder().s(user).build();
        item.put("destination", AttributeValue.builder().s(dest).build();
        item.put("flight_number", AttributeValue.builder().s(flight).build();
        item.put("status", AttributeValue.builder().s("CONFIRMED").build();

        PutItemRequest request = PutItemRequest.builder()
                .tablename(TABLE_NAME)
                .item(item)
                .build();

        DynamoDbClient.putItem(request);

    }
}
