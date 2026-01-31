package com.travel.api.service;

import com.travel.api.dto.BookingRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@Service
public class TravelService {

    @Value("${aws.api.url}")
    private String apiUrl;

    @Value("${aws.api.key}")
    private String apiKey;

    @Autowired
    private RestTemplate restTemplate;


    public ResponseEntity<String> callExternalApi(BookingRequest bookingRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-api-key", apiKey);
        HttpEntity<Object> entity=new HttpEntity<Object>(bookingRequest,headers);
        ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);

        return response;
        }


}
