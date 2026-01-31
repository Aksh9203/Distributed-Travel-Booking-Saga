package com.travel.api.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BookingRequest {

    private String userId;
    private String destination;
    private String userEmail;
    private Double amount;
    private String bookingId;
}
