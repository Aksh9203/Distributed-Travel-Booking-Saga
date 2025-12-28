package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.HashMap;
import java.util.Map;


class UndoFlightResponse{
    public String status;
    public String message;

    public UndoFlightResponse(String message, String status){
        this.message = message;
        this.status = status;
    }
}

public class UndoFlightHandler implements RequestHandler<Map<String,Object>,UndoFlightResponse>{

    private final DynamoDbClient dynamoDb;
    private final String TABLE_NAME = "Travel_Flights";

    public UndoFlightHandler(){
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Override
    public UndoFlightResponse handleRequest(Map<String,Object> input, Context context) {
        String bookingId = (String) input.get("bookingId");

        context.getLogger().log("Cancellation request received for Booking ID: " + bookingId);

        deleteFlight(bookingId);

        context.getLogger().log("Flight Cancelled successfully.");

         return new UndoFlightResponse("FLIGHT CANCEL","SUCCESSFULLY");

    }

    private void deleteFlight(String id){
        Map<String, AttributeValue> key = new HashMap<>();

        key.put("booking_id",AttributeValue.builder().s(id).build());

        DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build();

        dynamoDb.deleteItem(request);
    }

}