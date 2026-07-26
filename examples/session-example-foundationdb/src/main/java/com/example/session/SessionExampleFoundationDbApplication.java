package com.example.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * An ordinary Spring Boot web application whose HTTP sessions live in FoundationDB.
 *
 * <p>
 * There is nothing about the adapter here, and nothing about FoundationDB. The
 * application has {@code spring-boot-starter-session-data-redis} on its class path and a
 * host and a port in {@code application.properties}; what is listening on that port is
 * the adapter rather than Redis, and that is the whole of the difference.
 *
 * <p>
 * The native client FoundationDB insists on is worth saying out loud here, because it is
 * the one thing this backend asks of a deployment: it is installed beside the
 * <em>adapter</em>, and nothing on this application's class path links against anything.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SessionExampleFoundationDbApplication {

	public static void main(String[] args) {
		SpringApplication.run(SessionExampleFoundationDbApplication.class, args);
	}

}
