package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a stock Spring Session application against the adapter backed by a real etcd.
 *
 * <p>
 * Everything below the application is what a deployment runs: the shipped configuration
 * classes, {@code redis-adapter.backend=etcd}, a real Lettuce client over TCP, and an
 * etcd in a container. Nothing is mocked, and the application is configured exactly as it
 * would be against Redis.
 *
 * <p>
 * The mode is the indexed one, because it is the mode that can tell the difference
 * between a store and Redis: a session's death is announced by a keyspace notification
 * rather than by the call that killed it, and with etcd that notification travels through
 * etcd's watch. The cleanup job's cron is disabled so that nothing but the test and etcd
 * touches an expired key.
 */
@SpringBootTest(classes = EtcdBackendEndToEndTests.SessionApplication.class,
		properties = { "redis-adapter.backend=etcd", "redis-adapter.etcd.watch-retry-delay=100ms" })
class EtcdBackendEndToEndTests {

	private static final String PRINCIPAL_INDEX = FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

	private static final GenericContainer<?> etcd = new GenericContainer<>("quay.io/coreos/etcd:v3.7.1")
		.withExposedPorts(2379)
		.withCommand("etcd", "--advertise-client-urls", "http://0.0.0.0:2379", "--listen-client-urls",
				"http://0.0.0.0:2379")
		.waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	@DynamicPropertySource
	static void etcdEndpoint(DynamicPropertyRegistry properties) {
		etcd.start();
		properties.add("redis-adapter.etcd.endpoints",
				() -> "http://" + etcd.getHost() + ":" + etcd.getMappedPort(2379));
		// A keyspace of this run's own, so a container that outlives the test
		// (Testcontainers
		// reuses one within a JVM) never hands a later run an earlier one's sessions.
		properties.add("redis-adapter.etcd.key-prefix", () -> "/e2e-" + UUID.randomUUID() + "/");
	}

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private EtcdBackendProperties properties;

	@Autowired
	private SessionEventRecorder events;

	/**
	 * The plain case, which is also what says the keys really are in etcd: the session is
	 * written through Lettuce, read back through Lettuce, and found in the store the
	 * adapter was configured with.
	 */
	@Test
	void aSessionIsWrittenToEtcdAndReadBack() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		RedisSession loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(hash(sessionKey(saved.getId())).fields()).isNotEmpty();
		assertThat(store()).isInstanceOf(EtcdKeyValueStore.class);
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
	 * The whole point of the backend, end to end: nobody comes back to the session,
	 * etcd's own lease expiry removes the shadow key, etcd's watch reports it, the
	 * adapter turns it into an {@code expired} keyspace notification, and the application
	 * fires {@code SessionExpiredEvent}. No sweeper anywhere in the chain, and no
	 * polling.
	 */
	@Test
	void aSessionLeftToExpireFiresSessionExpiredEventFromEtcdsOwnExpiry() {
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
	 * etcd that has to hold for the watch as well, which sees every removal there is.
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
	 * What a second adapter replica is for. Another store on the same etcd keyspace
	 * stands in for one: it deletes a session this application never told it about, and
	 * the application — connected to <em>this</em> adapter — is told. That is the one
	 * thing the bundled in-memory backend cannot do, and the reason this backend exists.
	 */
	@Test
	void aSessionRemovedByAnotherAdapterStillReachesThisOnesSubscriber() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		try (EtcdKeyValueStore otherAdapter = EtcdKeyValueStore.builder()
			.endpoints(this.properties.endpoints())
			.keyPrefix(this.properties.keyPrefix(0))
			.build()) {
			// Exactly what Spring Session's own delete does: the shadow key first, whose
			// death is the announcement, then the session itself.
			otherAdapter.delete(shadowKey(saved.getId()));
			otherAdapter.delete(sessionKey(saved.getId()));
		}

		this.events.awaitEvent(SessionDeletedEvent.class, saved.getId());
	}

	/**
	 * Sessions live in etcd rather than in the adapter, so an adapter that goes away
	 * takes nothing with it. A second store on the same keyspace is what an adapter that
	 * started afterwards would read.
	 */
	@Test
	void aSessionOutlivesTheAdapterThatWroteIt() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		try (EtcdKeyValueStore restarted = EtcdKeyValueStore.builder()
			.endpoints(this.properties.endpoints())
			.keyPrefix(this.properties.keyPrefix(0))
			.build()) {
			RedisValue session = restarted.get(sessionKey(saved.getId()));

			assertThat(session).isInstanceOf(HashValue.class);
			assertThat(((HashValue) session).fields()).isNotEmpty();
		}
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
	@Import(AdapterServerTestConfiguration.class)
	static class SessionApplication {

		@Bean
		SessionEventRecorder sessionEventRecorder() {
			return new SessionEventRecorder();
		}

	}

}
