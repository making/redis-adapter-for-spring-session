package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives a stock Spring Session application in its simple, TTL-based mode
 * ({@code @EnableRedisHttpSession}) against the adapter, over a real Lettuce client and a
 * real TCP connection. Nothing here knows about the adapter's internals: the application
 * is configured exactly as it would be against Redis, so a green run means Spring Session
 * cannot tell the difference.
 *
 * <p>
 * Assertions on the backend are made through the {@link KeyValueStore} bean, which pins
 * down the key format that actually reached storage rather than trusting the round trip
 * alone.
 */
@SpringBootTest(classes = SimpleSessionEndToEndTests.SessionApplication.class)
class SimpleSessionEndToEndTests {

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private KeyValueStore store;

	/**
	 * Timestamps are compared in epoch milliseconds because that is the precision Spring
	 * Session itself stores them at: its mapper writes them as millisecond longs, so a
	 * reload against real Redis loses sub-millisecond digits in exactly the same way.
	 */
	@Test
	void aSavedSessionIsReadBackWithEveryAttributeAndTimestamp() {
		Session saved = create(session -> {
			session.setAttribute("user", "alice");
			session.setAttribute("visits", 3);
		});

		Session loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.getId()).isEqualTo(saved.getId());
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(loaded.<Integer>getAttribute("visits")).isEqualTo(3);
		assertThat(loaded.getCreationTime().toEpochMilli()).isEqualTo(saved.getCreationTime().toEpochMilli());
		assertThat(loaded.getLastAccessedTime().toEpochMilli()).isEqualTo(saved.getLastAccessedTime().toEpochMilli());
		assertThat(loaded.getMaxInactiveInterval()).isEqualTo(saved.getMaxInactiveInterval());
		assertThat(this.store.exists(sessionKey(saved.getId()))).isTrue();
	}

	@Test
	void anUnknownSessionIdIsNotFound() {
		assertThat(this.sessions.findById("does-not-exist")).isNull();
	}

	/**
	 * Removing an attribute stores a zero-length value rather than dropping the hash
	 * field, so the adapter has to hand that empty value back as a value.
	 */
	@Test
	void removingAnAttributeIsVisibleOnTheNextRead() {
		Session saved = create(session -> session.setAttribute("user", "alice"));
		mutate(this.sessions, saved.getId(), session -> session.removeAttribute("user"));

		Session loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isNull();
	}

	@Test
	void aDeletedSessionIsGoneFromTheBackend() {
		Session saved = create(session -> session.setAttribute("user", "alice"));

		this.sessions.deleteById(saved.getId());

		assertThat(this.sessions.findById(saved.getId())).isNull();
		assertThat(this.store.exists(sessionKey(saved.getId()))).isFalse();
	}

	/**
	 * Changing the session id is a {@code RENAME}: the session must arrive under the new
	 * key with its data intact and must no longer be reachable under the old one.
	 */
	@Test
	void changingTheSessionIdMovesTheSessionToTheNewKey() {
		Session saved = create(session -> session.setAttribute("user", "alice"));
		String originalId = saved.getId();

		String newId = changeSessionId(this.sessions, originalId);

		assertThat(newId).isNotEqualTo(originalId);
		Session loaded = this.sessions.findById(newId);
		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(this.sessions.findById(originalId)).isNull();
		assertThat(this.store.exists(sessionKey(originalId))).isFalse();
		assertThat(this.store.exists(sessionKey(newId))).isTrue();
	}

	@Test
	void aSessionStopsBeingReadableOnceItsMaxInactiveIntervalHasElapsed() {
		Session saved = create(session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "alice");
		});

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(this.sessions.findById(saved.getId())).isNull();
			assertThat(this.store.exists(sessionKey(saved.getId()))).isFalse();
		});
	}

	private Session create(Consumer<Session> customizer) {
		return create(this.sessions, customizer);
	}

	/**
	 * Creates, customizes and saves a session. The repository's session type is not
	 * visible from here, so it is captured as a type variable rather than named.
	 * @param repository the session repository
	 * @param customizer what to set on the new session
	 * @return the saved session
	 */
	private static <S extends Session> Session create(SessionRepository<S> repository, Consumer<Session> customizer) {
		S session = repository.createSession();
		customizer.accept(session);
		repository.save(session);
		return session;
	}

	private static <S extends Session> void mutate(SessionRepository<S> repository, String id,
			Consumer<Session> customizer) {
		S session = Objects.requireNonNull(repository.findById(id), () -> "no session under " + id);
		customizer.accept(session);
		repository.save(session);
	}

	private static <S extends Session> String changeSessionId(SessionRepository<S> repository, String id) {
		S session = Objects.requireNonNull(repository.findById(id), () -> "no session under " + id);
		String newId = session.changeSessionId();
		repository.save(session);
		return newId;
	}

	private static byte[] sessionKey(String sessionId) {
		return (AdapterServerTestConfiguration.SESSION_KEY_PREFIX + sessionId).getBytes(UTF_8);
	}

	@EnableAutoConfiguration
	@EnableRedisHttpSession
	@Import(AdapterServerTestConfiguration.class)
	static class SessionApplication {

	}

}
