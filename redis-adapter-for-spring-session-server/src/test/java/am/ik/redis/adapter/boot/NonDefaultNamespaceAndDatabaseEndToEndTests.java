package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.Map;
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

/**
 * Runs the indexed-mode application of {@link IndexedSessionEndToEndTests} off the
 * defaults: a namespace of the application's own and database 1.
 *
 * <p>
 * Everything else in the suite runs on namespace {@code spring:session} and database 0,
 * so an adapter that hard-coded either would pass all of it. The two are what every name
 * Spring Session uses is built out of — the keys, the channel it announces new sessions
 * on, the {@code __keyevent@<db>__} channels it listens for deaths on, and the key prefix
 * it filters those messages by — and a name that comes out wrong fails silently: no error
 * is raised, the event simply never arrives, and the application keeps sessions it should
 * have been told are gone.
 *
 * <p>
 * The database index is not a Spring Session property of its own. It reads it off the
 * connection factory, so {@code spring.data.redis.database=1} is the one setting that
 * moves the client and the channel names Spring Session derives together; they cannot
 * disagree through this path. The server has to be serving that database, which is what
 * {@code redis-adapter.databases=2} asks of the shipped configuration.
 *
 * <p>
 * Every assertion on storage is made against the backend of database 1, and its
 * counterpart against the backend of database 0, which must stay untouched. Asserting
 * only that the sessions work would leave the possibility that they worked because
 * everything quietly went to database 0 after all.
 */
@SpringBootTest(classes = NonDefaultNamespaceAndDatabaseEndToEndTests.SessionApplication.class,
		properties = { "redis-adapter.databases=2", "spring.data.redis.database=1" })
class NonDefaultNamespaceAndDatabaseEndToEndTests {

	/**
	 * The namespace the application runs under. It shares no prefix with Spring Session's
	 * default, so a key built from the wrong one cannot be mistaken for a key built from
	 * this one.
	 */
	private static final String NAMESPACE = "acme:web";

	/** The database the application's connection selects. */
	private static final int DATABASE = 1;

	private static final SessionKeys KEYS = new SessionKeys(NAMESPACE);

	private static final String PRINCIPAL_INDEX = FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private SessionEventRecorder events;

	/**
	 * The names Spring Session derived, asserted before anything is stored through them.
	 * They are the four strings the rest of this class exercises, and reading them back
	 * is what makes a later failure a failure of the adapter rather than of a test that
	 * turned out to be running on the defaults after all.
	 */
	@Test
	void springSessionDerivedItsNamesFromTheCustomNamespaceAndDatabase() {
		assertThat(this.sessions.getSessionCreatedChannelPrefix()).isEqualTo(KEYS.createdChannelPrefix(DATABASE));
		assertThat(this.sessions.getSessionDeletedChannel()).isEqualTo("__keyevent@1__:del");
		assertThat(this.sessions.getSessionExpiredChannel()).isEqualTo("__keyevent@1__:expired");
		assertThat(this.databases.databases()).hasSize(2);
	}

	/**
	 * A saved session must be in the backend of database 1, under the custom namespace,
	 * and nowhere else. The default-namespace key is asserted absent from that same
	 * backend as well: a namespace that was ignored would put the session there, and the
	 * database assertions alone would not notice.
	 */
	@Test
	void aSavedSessionLandsInDatabaseOneUnderTheCustomNamespace() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		assertThat(store().exists(KEYS.session(saved.getId()))).isTrue();
		assertThat(store().exists(KEYS.shadow(saved.getId()))).isTrue();
		assertThat(store().exists(SessionKeys.DEFAULT.session(saved.getId())))
			.as("the session is stored under the configured namespace, not Spring Session's default")
			.isFalse();
		assertThat(defaultDatabase().exists(KEYS.session(saved.getId())))
			.as("nothing of database 1 may reach the backend of database 0")
			.isFalse();
		assertThat(defaultDatabase().exists(KEYS.shadow(saved.getId()))).isFalse();

		RedisSession loaded = this.sessions.findById(saved.getId());
		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
	}

	/**
	 * The created event travels the one channel the adapter does not invent: Spring
	 * Session publishes it itself, on a channel carrying both the namespace and the
	 * database index, and the adapter has to route it to the pattern the listener
	 * container subscribed with.
	 */
	@Test
	void savingANewSessionFiresSessionCreatedEvent() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		SessionCreatedEvent event = this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		assertThat(event.getSessionId()).isEqualTo(saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
	}

	/**
	 * The index is an ordinary set, so it moves with the namespace like any other key,
	 * and the lookup is asserted alongside the key itself: a lookup that happened to
	 * return nothing would pass either way.
	 */
	@Test
	void aPrincipalSessionIsFoundByItsIndexAndDroppedFromItWhenDeleted() {
		RedisSession saved = create(session -> session.setAttribute(PRINCIPAL_INDEX, "namespaced-alice"));

		Map<String, RedisSession> found = this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX,
				"namespaced-alice");

		assertThat(found).containsOnlyKeys(saved.getId());
		assertThat(store().exists(KEYS.principalIndex("namespaced-alice"))).isTrue();
		assertThat(defaultDatabase().exists(KEYS.principalIndex("namespaced-alice"))).isFalse();
		assertThat(store().exists(SessionKeys.DEFAULT.principalIndex("namespaced-alice"))).isFalse();

		this.sessions.deleteById(saved.getId());

		assertThat(this.sessions.findByIndexNameAndIndexValue(PRINCIPAL_INDEX, "namespaced-alice")).isEmpty();
		assertThat(store().exists(KEYS.principalIndex("namespaced-alice"))).isFalse();
	}

	/**
	 * Deleting the session removes its shadow key on database 1, and the {@code del}
	 * notification that removal emits has to be published on {@code __keyevent@1__:del} —
	 * the channel Spring Session subscribed to — with a body starting with the custom
	 * namespace, which is what it filters incoming messages by. Either name coming out
	 * wrong leaves the application never told the session has gone.
	 */
	@Test
	void deletingASessionFiresSessionDeletedEvent() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		this.events.awaitEvent(SessionCreatedEvent.class, saved.getId());

		this.sessions.deleteById(saved.getId());

		SessionDeletedEvent event = this.events.awaitEvent(SessionDeletedEvent.class, saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(this.sessions.findById(saved.getId())).isNull();
		assertThat(store().exists(KEYS.shadow(saved.getId()))).isFalse();
	}

	/**
	 * The same for the expiry the backend notices on its own: the sweeper of the database
	 * 1 backend has to publish on {@code __keyevent@1__:expired}, naming the shadow key
	 * under the custom namespace.
	 */
	@Test
	void aSessionLeftToExpireFiresSessionExpiredEvent() {
		RedisSession saved = create(session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "alice");
		});

		SessionExpiredEvent event = this.events.awaitEvent(SessionExpiredEvent.class, saved.getId());

		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(store().exists(KEYS.shadow(saved.getId()))).isFalse();
		assertThat(this.events.eventsOf(SessionDeletedEvent.class, saved.getId())).isEmpty();
	}

	/**
	 * Returns the backend the application's sessions land in, which is the one of the
	 * database its connection selected.
	 * @return the backend of database 1
	 */
	private KeyValueStore store() {
		return this.databases.database(DATABASE);
	}

	/**
	 * Returns the backend of the database nothing here uses. Every key of this
	 * application must be absent from it, which is what proves the keys really went
	 * elsewhere rather than the assertions above having been made on the store that
	 * receives everything anyway.
	 * @return the backend of database 0
	 */
	private KeyValueStore defaultDatabase() {
		return this.databases.database(0);
	}

	private RedisSession create(Consumer<RedisSession> customizer) {
		RedisSession session = this.sessions.createSession();
		customizer.accept(session);
		this.sessions.save(session);
		return session;
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(redisNamespace = NAMESPACE, cleanupCron = Scheduled.CRON_DISABLED)
	@Import(AdapterServerTestConfiguration.class)
	static class SessionApplication {

		@Bean
		SessionEventRecorder sessionEventRecorder() {
			return new SessionEventRecorder();
		}

	}

}
