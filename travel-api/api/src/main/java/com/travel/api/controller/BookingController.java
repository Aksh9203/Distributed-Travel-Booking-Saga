package com.travel.api.controller;


import com.travel.api.dto.BookingRequest;
import com.travel.api.service.TravelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final TravelService travelService;

    public BookingController(TravelService travelService) {
        this.travelService = travelService;
    }

    @PostMapping("/book")
    public ResponseEntity<String> intiateBooking(@RequestBody BookingRequest bookingRequest) {
        ResponseEntity<String> result = travelService.callExternalApi(bookingRequest);
        return result;
    }

}
