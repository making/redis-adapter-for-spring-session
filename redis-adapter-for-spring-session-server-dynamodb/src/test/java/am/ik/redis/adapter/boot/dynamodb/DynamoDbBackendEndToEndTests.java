package am.ik.redis.adapter.boot.dynamodb;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.SessionEventRecorder;
import am.ik.redis.adapter.boot.SessionKeys;
import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a stock Spring Session application against the adapter backed by DynamoDB — the
 * Floci emulator standing in for it, which is the honest local best
 * ({@code .docs/design/architecture.md} §12.6).
 *
 * <p>
 * Everything below the application is what a deployment runs: the shipped configuration
 * classes of this server, the {@code DynamoDbClient} Spring Cloud AWS builds from
 * {@code spring.cloud.aws.*} properties, a real Lettuce client over TCP, and the emulator
 * in a container. Nothing is mocked, and the application is configured exactly as it
 * would be against Redis.
 *
 * <p>
 * The mode is the indexed one, because it is the mode that can tell the difference
 * between a store and Redis: a session's death is announced by a keyspace notification
 * rather than by the call that killed it, and with DynamoDB that notification travels
 * through the polled key-event log. The cleanup job's cron is disabled so that nothing
 * but the test and the sweeper touches an expired key.
 */
@SpringBootTest(classes = DynamoDbBackendEndToEndTests.SessionApplication.class,
		properties = { "redis-adapter.dynamodb.poll-interval=50ms", "redis-adapter.dynamodb.cursor-lag=200ms",
				"redis-adapter.dynamodb.sweep-interval=200ms" })
class DynamoDbBackendEndToEndTests {

	private static final String PRINCIPAL_INDEX = FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

	@DynamicPropertySource
	static void dynamoDb(DynamicPropertyRegistry properties) {
		properties.add("spring.cloud.aws.dynamodb.endpoint", () -> Floci.running().getEndpoint());
		properties.add("spring.cloud.aws.region.static", () -> Floci.running().getRegion());
		properties.add("spring.cloud.aws.credentials.access-key", () -> Floci.running().getAccessKey());
		properties.add("spring.cloud.aws.credentials.secret-key", () -> Floci.running().getSecretKey());
		// A table of this run's own, so a container that outlives the test never hands a
		// later run an earlier one's sessions.
		properties.add("redis-adapter.dynamodb.table-name", () -> "e2e-" + UUID.randomUUID());
	}

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private DynamoDbBackendProperties properties;

	@Autowired
	private DynamoDbClient client;

	@Autowired
	private SessionEventRecorder events;

	/**
	 * The plain case, which is also what says the keys really are in DynamoDB: the
	 * session is written through Lettuce, read back through Lettuce, and found in the
	 * store the adapter was configured with.
	 */
	@Test
	void aSessionIsWrittenToDynamoDbAndReadBack() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		RedisSession loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(hash(sessionKey(saved.getId())).fields()).isNotEmpty();
		assertThat(store()).isInstanceOf(DynamoDbKeyValueStore.class);
	}

	@Test
	void savingANewSessionFiresSessionCreatedEvent() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		SessionCreatedEvent event = this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
	}

	@Test
	void deletingASessionFiresSessionDeletedEvent() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		this.sessions.deleteById(saved.getId());

		SessionDeletedEvent event = this.events.awaitEvent(SessionDeletedEvent.class, saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(this.sessions.findById(saved.getId())).isNull();
	}

	/**
	 * The whole point of the backend, end to end: nobody comes back to the session, the
	 * deadline passes, the sweeper removes the shadow key and writes the announcement in
	 * the same transaction, the poller reads it, the adapter turns it into an
	 * {@code expired} keyspace notification, and the application fires
	 * {@code SessionExpiredEvent}. DynamoDB's own TTL is nowhere in the chain.
	 */
	@Test
	void aSessionLeftToExpireFiresSessionExpiredEventFromTheSweeper() {
		RedisSession saved = create(session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "alice");
		});

		SessionExpiredEvent event = this.events.awaitEvent(SessionExpiredEvent.class, saved.getId());

		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(store().exists(shadowKey(saved.getId()))).isFalse();
		assertThat(this.events.eventsOf(SessionDeletedEvent.class, saved.getId())).isEmpty();
	}

	@Test
	void sessionsAreFoundByPrincipalName() {
		RedisSession first = create(session -> session.setAttribute(PRINCIPAL_INDEX, "alice"));
		RedisSession second = create(session -> session.setAttribute(PRINCIPAL_INDEX, "alice"));

		assertThat(this.sessions.findByPrincipalName("alice")).containsOnlyKeys(first.getId(), second.getId());

		this.sessions.deleteById(first.getId());

		assertThat(this.sessions.findByPrincipalName("alice")).containsOnlyKeys(second.getId());
	}

	/**
	 * Changing the id renames the session's keys, and a rename must announce nothing: a
	 * {@code del} on the way would be read as the session having been destroyed. With
	 * this backend that has to hold for the key-event log as well, which every replica
	 * polls.
	 */
	@Test
	void changingTheSessionIdMovesTheSessionWithoutADeletedEvent() {
		RedisSession saved = create(session -> session.setAttribute(PRINCIPAL_INDEX, "renamed-alice"));
		String originalId = saved.getId();
		this.events.awaitEvent(SessionCreatedEvent.class, originalId);

		String newId = changeSessionId(originalId);

		assertThat(newId).isNotEqualTo(originalId);
		RedisSession loaded = this.sessions.findById(newId);
		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute(PRINCIPAL_INDEX)).isEqualTo("renamed-alice");
		assertThat(this.sessions.findById(originalId)).isNull();
		assertThat(store().exists(shadowKey(newId))).isTrue();
		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "renamed-alice"))
			.containsOnlyKeys(newId);
		this.events.assertNoEvent(SessionDeletedEvent.class, originalId, Duration.ofMillis(500));
	}

	/**
	 * What a second adapter replica is for. Another store on the same table stands in for
	 * one: it deletes a session this application never told it about, and the application
	 * — connected to <em>this</em> adapter — is told, through the polled log. That is the
	 * one thing the bundled in-memory backend cannot do.
	 */
	@Test
	void aSessionRemovedByAnotherAdapterStillReachesThisOnesSubscriber() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		try (DynamoDbKeyValueStore otherAdapter = DynamoDbKeyValueStore.builder()
			.client(this.client)
			.tableName(this.properties.tableName())
			.sweeperEnabled(false)
			.build()) {
			// Exactly what Spring Session's own delete does: the shadow key goes, and its
			// death is the announcement. The session hash stays behind (Spring Session
			// leaves it on a shortened TTL for the same reason), because the subscriber
			// reads the session back when the notification lands — which with a polled
			// log is a cursor lag later, not the same millisecond.
			otherAdapter.delete(shadowKey(saved.getId()));
		}

		this.events.awaitEvent(SessionDeletedEvent.class, saved.getId());
	}

	/**
	 * Sessions live in DynamoDB rather than in the adapter, so an adapter that goes away
	 * takes nothing with it.
	 */
	@Test
	void aSessionOutlivesTheAdapterThatWroteIt() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		try (DynamoDbKeyValueStore restarted = DynamoDbKeyValueStore.builder()
			.client(this.client)
			.tableName(this.properties.tableName())
			.sweeperEnabled(false)
			.build()) {
			RedisValue session = restarted.get(sessionKey(saved.getId()));

			assertThat(session).isInstanceOf(HashValue.class);
			assertThat(((HashValue) session).fields()).isNotEmpty();
		}
	}

	/**
	 * Where a session stops fitting, seen from the application rather than from the
	 * store. DynamoDB's item ceiling is 400 KB and no retry can change that, so what
	 * comes back through the client has to say so — an {@code internal error} would send
	 * whoever is holding this exception looking for a bug in the adapter, when what the
	 * application has to do is keep less in the session.
	 */
	@Test
	void aSessionAttributeBiggerThanOneItemIsRefusedWithAnErrorThatSaysWhy() {
		RedisSession session = this.sessions.createSession();
		session.setAttribute("blob", new byte[500 * 1024]);

		assertThatThrownBy(() -> this.sessions.save(session)).rootCause()
			.hasMessage("ERR value too large for the backend");
		assertThat(this.sessions.findById(session.getId())).isNull();
	}

	private KeyValueStore store() {
		return this.databases.database(0);
	}

	private HashValue hash(byte[] key) {
		RedisValue value = store().get(key);
		assertThat(value).as("what is under %s", new String(key)).isInstanceOf(HashValue.class);
		return (HashValue) Objects.requireNonNull(value);
	}

	private RedisSession create(Consumer<RedisSession> customizer) {
		RedisSession session = this.sessions.createSession();
		customizer.accept(session);
		this.sessions.save(session);
		return session;
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

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, DynamoDbBackendConfiguration.class })
	static class SessionApplication {

		@Bean
		SessionEventRecorder sessionEventRecorder() {
			return new SessionEventRecorder();
		}

	}

}
