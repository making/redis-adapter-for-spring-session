package com.example.session;

import java.util.function.Consumer;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.TestBackendConfiguration;
import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.ZSetValue;
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
 * Runs the alternative expiration store the README shows, declared as a bean beside
 * Spring Boot's auto-configuration rather than instead of it. That combination is the
 * point: the README tells a reader to ask for indexed mode with a property and to add
 * this one bean, and nothing else.
 *
 * <p>
 * What is asserted is the sorted set itself, in the backend. The expirations of an
 * indexed application go into a set per minute unless this bean is there, so a sorted set
 * holding the session is the proof that Spring Session picked the bean up.
 *
 * <p>
 * The two end-to-end tests in {@code am.ik.redis.adapter.boot} cover what this store then
 * does — the {@code ZREVRANGEBYSCORE} the cleanup job runs, and the entries it drops.
 */
@SpringBootTest(classes = ReadmeSortedSetExpirationExampleTests.Application.class)
class ReadmeSortedSetExpirationExampleTests {

	/**
	 * Asks Spring Boot for the indexed repository, with the README's own setting, since
	 * the expiration store is only used by that one.
	 * @param registry where the settings are added
	 */
	@DynamicPropertySource
	static void indexedMode(DynamicPropertyRegistry registry) {
		ReadmeSnippets.settings("readme/application.properties", "app-indexed")
			.forEach((key, value) -> registry.add(key, () -> value));
	}

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private KeyValueStores databases;

	@Test
	void theExampleRecordsExpirationsInOneSortedSet() {
		Session saved = save(this.sessions, session -> session.setAttribute("user", "alice"));

		RedisValue expirations = this.databases.database(0).get("spring:session:sessions:expirations".getBytes(UTF_8));

		assertThat(expirations).isInstanceOf(ZSetValue.class);
		assertThat(((ZSetValue) expirations).scores()).hasSize(1);
		assertThat(this.sessions.findById(saved.getId())).isNotNull();
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
	 * The application of the README: auto-configuration, plus the one bean that changes
	 * how expirations are recorded.
	 */
	@EnableAutoConfiguration
	@Import({ SortedSetExpirationConfig.class, AdapterServerTestConfiguration.class, TestBackendConfiguration.class })
	static class Application {

	}

}
