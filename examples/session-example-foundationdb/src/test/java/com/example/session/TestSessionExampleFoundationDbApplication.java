package com.example.session;

import org.springframework.boot.SpringApplication;

/**
 * Runs the example with a real FoundationDB and an adapter started for it.
 *
 * <p>
 * {@code ./mvnw spring-boot:test-run} and the page is on
 * <a href="http://localhost:8080">localhost:8080</a>, with nothing to install first — the
 * native client the adapter needs is put into its image by
 * {@link SessionStoreContainers}, not onto this machine.
 */
public class TestSessionExampleFoundationDbApplication {

	public static void main(String[] args) {
		SpringApplication.from(SessionExampleFoundationDbApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(args);
	}

}
