package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// The region between the markers below is quoted verbatim in README.md. It is the whole of
// the server module a backend author writes: this class, MyBackendConfiguration beside it,
// and a dependency on redis-adapter-for-spring-session-server.
// tag::backend-application[]
@SpringBootApplication
public class MyRedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyRedisAdapterServerApplication.class, args);
	}

}
// end::backend-application[]
