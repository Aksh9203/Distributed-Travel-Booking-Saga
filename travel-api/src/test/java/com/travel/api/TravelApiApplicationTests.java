package com.travel.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"aws.api.url=http://localhost/book", "aws.api.key=test"})
class TravelApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
