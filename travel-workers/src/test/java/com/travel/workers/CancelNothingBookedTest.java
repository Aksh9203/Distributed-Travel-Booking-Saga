package com.travel.workers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Cancelling a booking that was never written is a no-op success; any other cancelled transaction still fails. */
public class CancelNothingBookedTest {

    private static Object cancel(String service, String firstReason) {
        DynamoDbClient ddb = mock(DynamoDbClient.class);
        when(ddb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(CancellationReason.builder().code(firstReason).build(),
                        CancellationReason.builder().code("None").build())
                .build());
        Context ctx = mock(Context.class);
        when(ctx.getLogger()).thenReturn(mock(LambdaLogger.class));
        RequestHandler<Map<String, Object>, ?> handler = service.equals("flight") ? new FlightHandler(ddb) : new HotelHandler(ddb);
        return handler.handleRequest(Map.of("action", "CANCEL", "bookingId", "never-booked", "userEmail", "u@example.com"), ctx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"flight", "hotel"})
    void conditionalCheckFailedMeansNothingToUndo(String service) {
        assertNotNull(cancel(service, "ConditionalCheckFailed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"flight", "hotel"})
    void otherCancellationReasonsAreRethrown(String service) {
        assertThrows(TransactionCanceledException.class, () -> cancel(service, "TransactionConflict"));
    }
}
