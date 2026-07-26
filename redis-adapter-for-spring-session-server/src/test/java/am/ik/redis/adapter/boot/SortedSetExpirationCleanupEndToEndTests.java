package am.ik.redis.adapter.boot;

import java.time.Duration;

import am.ik.redis.adapter.store.KeyValueStore;
import com.example.session.SortedSetExpirationConfig;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the one thing the sorted-set expiration store does that nothing else does: its
 * cleanup job asks the sorted set which sessions are due and touches each of them, so
 * that a session nobody comes back to is reclaimed rather than left lying around.
 *
 * <p>
 * What it touches is the <strong>session</strong> key, not the shadow key the
 * minute-based store touches, so the touch produces no session event of its own — the
 * expired event comes from the shadow key dying on its own TTL, and is covered by
 * {@link SortedSetExpirationEndToEndTests}. Here the touch is observed by the only thing
 * it does: the stale session hash is evicted at the moment it is read.
 *
 * <p>
 * The backend's sweeper is therefore switched off: with it running, the hash would die of
 * the sweep and the test would pass without the touch ever mattering. The cleanup job's
 * cron is switched off too, so the only run of it is the one the test asks for.
 */
@SpringBootTest(classes = SortedSetExpirationCleanupEndToEndTests.SessionApplication.class,
		properties = TestBackendConfiguration.ACTIVE_EXPIRY_PROPERTY + "=false")
class SortedSetExpirationCleanupEndToEndTests {

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Test
	void theCleanupJobTouchesTheSessionsTheSortedSetSaysAreDue() {
		RedisSession saved = this.sessions.createSession();
		saved.setMaxInactiveInterval(Duration.ofSeconds(1));
		saved.setAttribute("user", "alice");
		this.sessions.save(saved);

		byte[] sessionKey = SessionKeys.DEFAULT.session(saved.getId());
		// A session hash outlives the session it holds by five minutes, which is how long
		// a test would otherwise wait for the touch to have anything to do. Bring its
		// deadline forward instead: what is being proved is that the touch happens at
		// all.
		long staleAt = store().currentTimeMillis() + 200;
		assertThat(store().expireAt(sessionKey, staleAt)).as("no session hash under %s", saved.getId()).isTrue();
		long dueAt = saved.getLastAccessedTime().plus(saved.getMaxInactiveInterval()).toEpochMilli();
		// Waiting on the store's own clock rather than on the store's contents: reading
		// the
		// key is what the test is about, and doing it early would expire the key itself.
		await().atMost(Duration.ofSeconds(10)).until(() -> store().currentTimeMillis() > Math.max(staleAt, dueAt));

		this.sessions.cleanUpExpiredSessions();

		assertThat(store().exists(sessionKey)).isFalse();
	}

	/**
	 * Returns the backend the application's sessions land in. The application is
	 * configured with the default database, which is the first of them.
	 * @return the backend of database 0
	 */
	private KeyValueStore store() {
		return this.databases.database(0);
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, TestBackendConfiguration.class, SortedSetExpirationConfig.class })
	static class SessionApplication {

	}

}
