package com.example.session;

import org.testcontainers.containers.GenericContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * What start.spring.io wires up for a Redis-backed application, with the adapter in
 * Redis's place — and a profile that puts Redis back.
 *
 * <p>
 * {@code @ServiceConnection(name = "redis")} is what makes the substitution invisible:
 * the application is given {@code spring.data.redis.host} and {@code port} pointing at
 * the container, whichever container that is. Nothing in {@code src/main} changes between
 * the two, and nothing in it knows which one it got.
 *
 * <p>
 * The adapter is the default because it is what this example is about. Activate the
 * {@code redis} profile — {@code -Dspring.profiles.active=redis} for the tests,
 * {@code -Dspring-boot.run.profiles=redis} for {@code spring-boot:test-run} — and the
 * same application runs against a real Redis instead. That is how a difference between
 * the two is found rather than argued about.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@Profile("!redis")
	@ServiceConnection(name = "redis")
	GenericContainer<?> adapterContainer() {
		return SessionStoreContainers.adapter();
	}

	@Bean
	@Profile("redis")
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return SessionStoreContainers.redis();
	}

}
