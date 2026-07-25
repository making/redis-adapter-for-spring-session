package com.example.session;

import java.util.function.Consumer;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the namespace and database example the README shows.
 *
 * <p>
 * Both settings are asserted where they can only be answered, in the backend: the session
 * has to be in the store of database 1, under the namespace the example names, and
 * nothing of it may be in the store of database 0. A setting that was ignored would put
 * it there instead, and a round trip through the repository alone would not notice.
 *
 * <p>
 * The server is told to serve two databases, which is the one thing the example says an
 * operator has to do on the adapter side.
 */
@SpringBootTest(classes = ReadmeNamespaceExampleTests.Application.class, properties = "redis-adapter.databases=2")
class ReadmeNamespaceExampleTests {

	/**
	 * Applies the README's own namespace and database settings.
	 * @param registry where the settings are added
	 */
	@DynamicPropertySource
	static void namespaceAndDatabase(DynamicPropertyRegistry registry) {
		ReadmeSnippets.settings("readme/application.properties", "app-namespace")
			.forEach((key, value) -> registry.add(key, () -> value));
	}

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private KeyValueStores databases;

	@Test
	void theExampleMovesTheSessionsToItsOwnNamespaceAndDatabase() {
		Session saved = save(this.sessions, session -> session.setAttribute("user", "alice"));

		assertThat(this.sessions.findById(saved.getId())).isNotNull();
		assertThat(this.databases.database(1).exists(key("acme:web:sessions:" + saved.getId()))).isTrue();
		assertThat(this.databases.database(1).exists(key("spring:session:sessions:" + saved.getId()))).isFalse();
		assertThat(this.databases.database(0).exists(key("acme:web:sessions:" + saved.getId()))).isFalse();
	}

	private static byte[] key(String name) {
		return name.getBytes(UTF_8);
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
