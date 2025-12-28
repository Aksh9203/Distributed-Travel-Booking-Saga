package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.HashMap;
import java.util.Map;

class UndoHotelResponse{
    public String status;
    public String message;

    public UndoHotelResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }
}

public class UndoHotelHandler implements RequestHandler<Map<String,Object>,UndoHotelResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Hotels";

    public UndoHotelHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Override
    public UndoHotelResponse handleRequest(Map<String,Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");

        context.getLogger().log("Cancellation request for Booking ID: " + bookingId);

        deleteHotel(bookingId);

        context.getLogger().log("Hotel Cancelled successfully.");

        return new UndoHotelResponse("HOTEL CANCELLED", "SUCCESSFULLY");

    }

    private void deleteHotel(String id){
        Map<String,AttributeValue> key = new HashMap<>();

        key.put("booking_id",AttributeValue.builder().s(id).build());

        DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build();

        dynamoDb.deleteItem(request);
    }
}