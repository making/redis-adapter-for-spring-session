package com.example.session;

import java.util.function.Consumer;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.KeyValueStores;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the quick start the README shows: an application that adds the Spring Session
 * starter, names where the adapter listens, and configures nothing else. There is no
 * session configuration class and no {@code @EnableRedisHttpSession} here on purpose —
 * Spring Boot's auto-configuration is what the README tells a reader to rely on, so it is
 * what the test has to prove.
 *
 * <p>
 * The saved session is looked for in the adapter's own backend as well as through the
 * repository, so that a passing test means the session really crossed the wire and landed
 * under the key the README documents.
 */
@SpringBootTest(classes = ReadmeSimpleModeExampleTests.Application.class)
class ReadmeSimpleModeExampleTests {

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private KeyValueStores databases;

	@Test
	void theExampleStoresAndReadsBackASession() {
		Session saved = save(this.sessions, session -> session.setAttribute("user", "alice"));

		Session loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(this.databases.database(0).exists(("spring:session:sessions:" + saved.getId()).getBytes(UTF_8)))
			.isTrue();
	}

	/**
	 * Creates, customizes and saves a session. The repository's session type is not
	 * visible from here, so it is captured as a type variable rather than named.
	 * @param repository the session repository
	 * @param customizer what to set on the new session
	 * @return the saved session
	 */
	private static <S extends Session> Session save(SessionRepository<S> repository, Consumer<Session> customizer) {
		S session = repository.createSession();
		customizer.accept(session);
		repository.save(session);
		return session;
	}

	/**
	 * The application of the README: nothing but auto-configuration, with the adapter it
	 * talks to started beside it.
	 */
	@EnableAutoConfiguration
	@Import(AdapterServerTestConfiguration.class)
	static class Application {

	}

}
