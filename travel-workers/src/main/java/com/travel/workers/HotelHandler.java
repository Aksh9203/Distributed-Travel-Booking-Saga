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
class HotelRequest {
    public String bookingId;
    public String userId;
    public String destination;

    public HotelRequest (){}
}

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
                .build();
    }

    @Override
    public HotelResponse handleRequest(Map<String,Object> input, Context context) {

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
        savingBooking(bookingId,userId,destination,hotelNumber);

        return new HotelResponse(bookingId,"SUCCESS","Hotel Booked",hotelNumber);

    }

    private void savingBooking(String id, String user, String dest, String hotel){
        Map<String,AttributeValue> item = new HashMap<>();
        item.put("booking_id", AttributeValue.builder().s(id).build());
        item.put("user_id", AttributeValue.builder().s(user).build());
        item.put("destination", AttributeValue.builder().s(dest).build());
        item.put("hotel_number", AttributeValue.builder().s(hotel).build());
        item.put("status", AttributeValue.builder().s("CONFIRMED").build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);

    }
}