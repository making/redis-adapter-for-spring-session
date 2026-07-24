package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.ZSetValue;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.events.SessionExpiredEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives an indexed Spring Session application that has opted into the sorted-set
 * expiration store against the adapter, over a real Lettuce client and a real TCP
 * connection.
 *
 * <p>
 * The store replaces the minute buckets of {@link IndexedSessionEndToEndTests} with one
 * sorted set holding every live session, scored by the epoch millisecond it is due to
 * expire at. That is the whole of the difference: a session's death is still announced by
 * the shadow key's keyspace notification, so the sorted set only decides <em>which</em>
 * sessions the cleanup job goes and looks at — the path
 * {@link SortedSetExpirationCleanupEndToEndTests} covers.
 *
 * <p>
 * The sorted set is shared by every test here, as it is by every session of an
 * application, so each test asserts on its own session's member rather than on the whole
 * of it.
 */
@SpringBootTest(classes = SortedSetExpirationEndToEndTests.SessionApplication.class)
class SortedSetExpirationEndToEndTests {

	/**
	 * What the cleanup job asks for at a time, and enough to see a test's own session.
	 */
	private static final int CLEANUP_COUNT = 100;

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private SessionEventRecorder events;

	/**
	 * Saving a session scores it by the moment it is due to expire, which is the last
	 * time it was accessed plus how long it may stay idle. The score is asserted on the
	 * backend as well as through a query, because a round trip alone would pass on any
	 * score both sides happened to agree on.
	 */
	@Test
	void savingASessionScoresItInTheSortedSetByWhenItIsDueToExpire() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		long dueAt = saved.getLastAccessedTime().plus(saved.getMaxInactiveInterval()).toEpochMilli();
		assertThat(expirations()).containsEntry(member(saved.getId()), (double) dueAt);
		assertThat(dueAtOrBefore(dueAt)).contains(saved.getId());
	}

	/**
	 * A session that is due later than the query asks about is not yet due, which is what
	 * keeps the cleanup job from touching every live session once a minute.
	 */
	@Test
	void aSessionThatIsNotDueYetIsNotAnswered() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));

		long dueAt = saved.getLastAccessedTime().plus(saved.getMaxInactiveInterval()).toEpochMilli();

		assertThat(dueAtOrBefore(dueAt - 1)).doesNotContain(saved.getId());
	}

	/**
	 * Deleting a session takes it out of the sorted set, and the save that ends the
	 * delete — the one that leaves the session with no idle time left — may put it back,
	 * scored as due already. Which of the two the sorted set is left holding depends on
	 * whether the {@code del} notification is handled before that last save, and Spring
	 * Session behaves the same way against Redis itself. Either way the session stops
	 * being scheduled to expire later, which is the whole of what the sorted set is for.
	 */
	@Test
	void deletingASessionStopsItFromBeingScheduledToExpireLater() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		long dueAt = saved.getLastAccessedTime().plus(saved.getMaxInactiveInterval()).toEpochMilli();
		assertThat(expirations()).containsEntry(member(saved.getId()), (double) dueAt);

		this.sessions.deleteById(saved.getId());

		assertThat(expirations()).doesNotContainEntry(member(saved.getId()), (double) dueAt);
	}

	/**
	 * Changing a session's id moves it in the sorted set: the entry under the old id is
	 * removed, and the save that follows records the session under its new one, because
	 * the id the cleanup job finds is the id it goes and touches. That save has to have
	 * something to write — a save with an empty delta writes nothing at all — so the
	 * session is changed as well as renamed.
	 */
	@Test
	void changingTheSessionIdMovesTheEntryToTheNewId() {
		RedisSession saved = create(session -> session.setAttribute("user", "alice"));
		String originalId = saved.getId();
		RedisSession loaded = Objects.requireNonNull(this.sessions.findById(originalId));

		String newId = loaded.changeSessionId();
		loaded.setAttribute("user", "bob");
		this.sessions.save(loaded);

		assertThat(expirations()).doesNotContainKey(member(originalId)).containsKey(member(newId));
	}

	/**
	 * A session nobody comes back to is still killed by the shadow key dying on its own
	 * TTL — the sorted set changes which sessions are looked at, not what an expiry is —
	 * and the repository takes the session out of the sorted set as it announces it.
	 */
	@Test
	void aSessionLeftToExpireFiresSessionExpiredEventAndLeavesTheSortedSet() {
		RedisSession saved = create(session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "alice");
		});

		SessionExpiredEvent event = this.events.awaitEvent(SessionExpiredEvent.class, saved.getId());

		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(expirations()).doesNotContainKey(member(saved.getId()));
	}

	/**
	 * Runs the query the cleanup job runs, through a real client: the sessions due at or
	 * before a moment, newest first.
	 * @param at the moment to ask about, in epoch millis
	 * @return the ids of the sessions that are due
	 */
	private Set<Object> dueAtOrBefore(long at) {
		return Objects.requireNonNull(this.sessions.getSessionRedisOperations()
			.opsForZSet()
			.reverseRangeByScore(SessionKeys.DEFAULT.expirationsSortedSet(), 0, at, 0, CLEANUP_COUNT));
	}

	/**
	 * Returns what the sorted set holds, read off the backend. A sorted set that has lost
	 * its last member is gone, as it is in Redis, so an absent key and an empty sorted
	 * set are the same answer.
	 * @return the member-to-score map, empty if the key is not there
	 */
	private Map<ByteArrayKey, Double> expirations() {
		return switch (store().get(SessionKeys.DEFAULT.expirationsSortedSetKey())) {
			case null -> Map.of();
			case ZSetValue sortedSet -> sortedSet.scores();
			case RedisValue other -> throw new AssertionError("the expirations key holds " + other);
		};
	}

	/**
	 * Returns a session id as it appears in the sorted set: serialized by the same
	 * serializer the application's template uses, and opaque to the adapter.
	 * @param sessionId the session id
	 * @return the member bytes
	 */
	private static ByteArrayKey member(String sessionId) {
		return ByteArrayKey.of(Objects.requireNonNull(RedisSerializer.java().serialize(sessionId)));
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

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, SortedSetExpirationStoreConfiguration.class })
	static class SessionApplication {

		@Bean
		SessionEventRecorder sessionEventRecorder() {
			return new SessionEventRecorder();
		}

	}

}
