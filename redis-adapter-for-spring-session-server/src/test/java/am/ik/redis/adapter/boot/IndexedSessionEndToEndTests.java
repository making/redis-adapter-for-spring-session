package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Drives a stock Spring Session application in its indexed mode
 * ({@code @EnableRedisIndexedHttpSession}) against the adapter, over a real Lettuce
 * client and a real TCP connection.
 *
 * <p>
 * Indexed mode is where Spring Session stops using Redis as a map and starts using it as
 * an event source: a session's death is announced by the keyspace notification of a
 * shadow key rather than by the call that killed it, and the sessions of one user are
 * found through a set the application maintains by hand. This is the acceptance gate for
 * replacing Redis outright, because it is the mode that can tell the difference.
 *
 * <p>
 * The application is configured exactly as it would be against Redis, with one exception:
 * the cleanup job's cron is disabled so that the only thing that touches an expired key
 * is whatever the test asks to. The path that job drives is covered by
 * {@link IndexedSessionCleanupEndToEndTests}, which triggers it directly.
 *
 * <p>
 * No {@code ConfigureRedisAction.NO_OP} bean is declared: the adapter answers
 * {@code CONFIG GET notify-keyspace-events} with the flags Spring Session insists on, so
 * the stock start-up check passes against it as it does against Redis.
 */
@SpringBootTest(classes = IndexedSessionEndToEndTests.SessionApplication.class)
class IndexedSessionEndToEndTests {

	private static final String PRINCIPAL_INDEX = FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private SessionEventRecorder events;

	/**
	 * The created event is the one message the adapter does not invent: Spring Session
	 * publishes it itself, to a channel naming the session, and the adapter's only job is
	 * to route it to the pattern the listener container subscribed with.
	 */
	@Test
	void savingANewSessionFiresSessionCreatedEvent() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		SessionCreatedEvent event = this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		assertThat(event.getSessionId()).isEqualTo(saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
	}

	/**
	 * The principal index is an ordinary set the application adds to on save and takes
	 * from on delete. Its key is asserted on directly, because a lookup that happened to
	 * return nothing would pass either way.
	 *
	 * <p>
	 * A principal name is never shared between tests: the index has no expiry and the
	 * tests share one application, so two of them under one name would see each other's
	 * sessions.
	 */
	@Test
	void aPrincipalSessionIsFoundByItsIndexAndDroppedFromItWhenDeleted() {
		RedisSession saved = create(session -> session.setAttribute(PRINCIPAL_INDEX, "indexed-alice"));

		Map<String, RedisSession> found = this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "indexed-alice");

		assertThat(found).containsOnlyKeys(saved.getId());
		assertThat(store().exists(principalKey("indexed-alice"))).isTrue();

		this.sessions.deleteById(saved.getId());

		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "indexed-alice")).isEmpty();
		assertThat(store().exists(principalKey("indexed-alice"))).isFalse();
	}

	@Test
	void changingThePrincipalMovesTheSessionBetweenIndexes() {
		RedisSession saved = create(session -> session.setAttribute(PRINCIPAL_INDEX, "moved-from"));

		mutate(saved.getId(), session -> session.setAttribute(PRINCIPAL_INDEX, "moved-to"));

		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "moved-from")).isEmpty();
		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "moved-to"))
			.containsOnlyKeys(saved.getId());
		assertThat(store().exists(principalKey("moved-from"))).isFalse();
	}

	/**
	 * Deleting a session deletes its shadow key, and the {@code del} keyspace
	 * notification that removal emits is the only thing that tells Spring Session a
	 * session has gone. The delete is then repeated: the second one finds the shadow key
	 * already gone and must therefore announce nothing, or an application would see a
	 * session destroyed twice.
	 */
	@Test
	void deletingASessionFiresSessionDeletedEventExactlyOnce() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		this.sessions.deleteById(saved.getId());

		SessionDeletedEvent event = this.events.awaitEvent(SessionDeletedEvent.class, saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(this.sessions.findById(saved.getId())).isNull();
		assertThat(store().exists(shadowKey(saved.getId()))).isFalse();

		this.sessions.deleteById(saved.getId());

		assertThat(this.events.eventsOf(SessionDeletedEvent.class, saved.getId())).hasSize(1);
	}

	/**
	 * A session nobody comes back to is killed by the backend's own sweeper, and the
	 * {@code expired} notification that eviction emits must name the shadow key so that
	 * Spring Session recognizes it. The session hash outlives the shadow key by five
	 * minutes for exactly this reason: the event carries only an id, and the repository
	 * still has to load the session to hand it to the application.
	 */
	@Test
	void aSessionLeftToExpireFiresSessionExpiredEventWhenTheSweeperEvictsTheShadowKey() {
		RedisSession saved = create(session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "alice");
		});

		SessionExpiredEvent event = this.events.awaitEvent(SessionExpiredEvent.class, saved.getId());

		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(store().exists(shadowKey(saved.getId()))).isFalse();
		assertThat(this.events.eventsOf(SessionDeletedEvent.class, saved.getId())).isEmpty();
	}

	/**
	 * Changing the id renames the session hash and the shadow key. The shadow key must
	 * move rather than be deleted and recreated: a {@code del} on the way would be read
	 * as the session having been destroyed.
	 */
	@Test
	void changingTheSessionIdRenamesBothTheHashAndTheShadowKey() {
		RedisSession saved = create(session -> session.setAttribute(PRINCIPAL_INDEX, "renamed-alice"));
		String originalId = saved.getId();
		this.events.awaitEvent(SessionCreatedEvent.class, originalId);

		String newId = changeSessionId(originalId);

		assertThat(newId).isNotEqualTo(originalId);
		RedisSession loaded = this.sessions.findById(newId);
		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute(PRINCIPAL_INDEX)).isEqualTo("renamed-alice");
		assertThat(this.sessions.findById(originalId)).isNull();
		assertThat(store().exists(sessionKey(originalId))).isFalse();
		assertThat(store().exists(shadowKey(originalId))).isFalse();
		assertThat(store().exists(shadowKey(newId))).isTrue();
		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "renamed-alice"))
			.containsOnlyKeys(newId);
		this.events.assertNoEvent(SessionDeletedEvent.class, originalId, Duration.ofMillis(200));
	}

	/**
	 * Renaming a key that is no longer there must fail with the error Redis gives,
	 * because Spring Session swallows that one error by its wording and rethrows anything
	 * else. A session whose keys have gone must therefore be able to change its id
	 * without the caller seeing a failure.
	 */
	@Test
	void changingTheSessionIdOfAVanishedSessionIsSwallowed() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		RedisSession loaded = Objects.requireNonNull(this.sessions.findById(saved.getId()));
		store().delete(sessionKey(saved.getId()));
		store().delete(shadowKey(saved.getId()));

		assertThatCode(() -> {
			loaded.changeSessionId();
			this.sessions.save(loaded);
		}).doesNotThrowAnyException();
	}

	/**
	 * Returns the backend the application's sessions land in. The application is
	 * configured with the default database, which is the first of them.
	 * @return the backend of database 0
	 */
	private KeyValueStore store() {
		return this.databases.database(0);
	}

	private RedisSession create(Consumer<RedisSession> customizer) {
		RedisSession session = this.sessions.createSession();
		customizer.accept(session);
		this.sessions.save(session);
		return session;
	}

	private void mutate(String id, Consumer<RedisSession> customizer) {
		RedisSession session = Objects.requireNonNull(this.sessions.findById(id), () -> "no session under " + id);
		customizer.accept(session);
		this.sessions.save(session);
	}

	private String changeSessionId(String id) {
		RedisSession session = Objects.requireNonNull(this.sessions.findById(id), () -> "no session under " + id);
		String newId = session.changeSessionId();
		this.sessions.save(session);
		return newId;
	}

	private static byte[] sessionKey(String sessionId) {
		return SessionKeys.DEFAULT.session(sessionId);
	}

	private static byte[] shadowKey(String sessionId) {
		return SessionKeys.DEFAULT.shadow(sessionId);
	}

	private static byte[] principalKey(String principal) {
		return SessionKeys.DEFAULT.principalIndex(principal);
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, TestBackendConfiguration.class, TestBackendConfiguration.class })
	static class SessionApplication {

		@Bean
		SessionEventRecorder sessionEventRecorder() {
			return new SessionEventRecorder();
		}

	}

}
