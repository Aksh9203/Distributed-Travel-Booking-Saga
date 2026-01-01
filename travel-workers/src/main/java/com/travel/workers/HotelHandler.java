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


//Output
class HotelResponse {
    public String bookingId;
    public String status;
    public String message;
    public String hotelNumber;

    public HotelResponse (String bookingId, String status, String message, String hotelNumber) {
        this.bookingId = bookingId;
        this.status = status;
        this.message = message;
        this.hotelNumber = hotelNumber;
    }
}


//Handler(Actual lambda logic)
public class HotelHandler implements RequestHandler<Map<String,Object>,HotelResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Hotels";

    public HotelHandler() {
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public HotelResponse handleRequest(Map<String,Object> input, Context context) {
        String action = (String)input.getOrDefault("action","BOOK");

        if("CANCEL".equalsIgnoreCase(action)){
            return cancelHotel(input,context);
        }
        else{
            return bookHotel(input,context);
        }
    }

    private HotelResponse bookHotel(Map<String,Object> input, Context context) {
        //Extract data from input
        String bookingId = (String)input.getOrDefault("bookingId",UUID.randomUUID().toString());
        String userId = (String)input.get("userId");
        String destination = (String)input.get("destination");

        context.getLogger().log("Hotel COnfirmed: " + destination);

        //Failure Scenario
        if("Atlantis".equalsIgnoreCase(destination)){
            throw new RuntimeException("Hotel is full. Try Later");
        }

        String hotelNumber = "HL-" + (int)(Math.random()*100);

        context.getLogger().log("Hotel Number is: " + hotelNumber);

        //Saving result to dynamoDb
        saveBookingStatus(bookingId,userId,destination,hotelNumber,"CONFIRMED");
        return new HotelResponse(bookingId,"SUCCESS","Hotel Booked",hotelNumber);
    }

    private HotelResponse cancelHotel(Map<String,Object> input ,Context context){
        String bookingId = (String) input.get("bookingId");
        String flightNumber = (String) input.get("flightNumber");

        context.getLogger().log("Compensating Transaction: Cancelling Hotel for Booking ID: " + bookingId);

        updateStatusToCancelled(bookingId);
        return new HotelResponse(bookingId,"SUCCESS","Hotel Cancelled (Compensated)",flightNumber);
    }

    private void updateStatusToCancelled(String id){
        Map<String,AttributeValue> key = new HashMap<>();
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

    private void saveBookingStatus(String id, String user, String dest, String hotel, String status){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());

        //To avoid null data errors
        if(user != null) item.put("user_id", AttributeValue.builder().s(user).build());
        if(dest != null) item.put("destination", AttributeValue.builder().s(dest).build());
        if(hotel != null) item.put("hotel_number", AttributeValue.builder().s(hotel).build());

        item.put("status", AttributeValue.builder().s(status).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}