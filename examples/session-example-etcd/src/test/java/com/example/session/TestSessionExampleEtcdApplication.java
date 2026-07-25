package com.example.session;

import org.springframework.boot.SpringApplication;

/**
 * Runs the example with an etcd and an adapter started for it.
 *
 * <p>
 * {@code ./mvnw spring-boot:test-run} and the page is on
 * <a href="http://localhost:8080">localhost:8080</a>, with nothing to install first.
 */
public class TestSessionExampleEtcdApplication {

	public static void main(String[] args) {
		SpringApplication.from(SessionExampleEtcdApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
