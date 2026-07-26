package com.example.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * An ordinary Spring Boot web application whose HTTP sessions live in DynamoDB.
 *
 * <p>
 * There is nothing about the adapter here, and nothing about DynamoDB. The application
 * has {@code spring-boot-starter-session-data-redis} on its class path and a host and a
 * port in {@code application.properties}; what is listening on that port is the adapter
 * rather than Redis, and that is the whole of the difference.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SessionExampleDynamoDbApplication {

	public static void main(String[] args) {
		SpringApplication.run(SessionExampleDynamoDbApplication.class, args);
	}

}
