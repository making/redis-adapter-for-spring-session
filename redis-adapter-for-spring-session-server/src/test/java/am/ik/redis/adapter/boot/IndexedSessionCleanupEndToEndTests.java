package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.events.SessionExpiredEvent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the other way a session-expired event is produced: not by the backend noticing a
 * key has died, but by Spring Session's cleanup job going and touching it.
 *
 * <p>
 * Redis makes no promise about when it evicts an expired key, so Spring Session does not
 * rely on the promise. It records every session in a set named after the minute the
 * session is due to expire in, and once a minute reads the set for the minute just gone
 * and runs {@code EXISTS} over each shadow key in it. Against Redis that read is what
 * forces the lazy expiry, and with it the {@code expired} notification. The adapter has
 * to behave the same way, which is why its backend expires a key on the access that finds
 * it stale rather than only on a sweep.
 *
 * <p>
 * The backend's sweeper is therefore switched off here: with it running, a key would die
 * of the sweep and the test would pass without the touch ever mattering. The cleanup
 * job's cron is switched off too, so the only run of it is the one the test asks for.
 */
@SpringBootTest(classes = IndexedSessionCleanupEndToEndTests.SessionApplication.class,
		properties = AdapterServerTestConfiguration.ACTIVE_EXPIRY_PROPERTY + "=false")
class IndexedSessionCleanupEndToEndTests {

	private static final long MILLIS_PER_MINUTE = Duration.ofMinutes(1).toMillis();

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private SessionEventRecorder events;

	@Test
	void theCleanupJobTouchesAnExpiredShadowKeyAndThatFiresSessionExpiredEvent() {
		RedisSession saved = this.sessions.createSession();
		saved.setMaxInactiveInterval(Duration.ofSeconds(1));
		saved.setAttribute("user", "alice");
		this.sessions.save(saved);

		byte[] shadowKey = shadowKey(saved.getId());
		long expireAt = Objects.requireNonNull(store().getExpireAt(shadowKey), "the shadow key carries no expiry");
		// Waiting on the store's own clock rather than on the store's contents: reading a
		// key is what the test is about, and doing it early would expire the key itself.
		await().atMost(Duration.ofSeconds(10)).until(() -> store().currentTimeMillis() > expireAt);
		assertThat(this.events.eventsOf(SessionExpiredEvent.class, saved.getId()))
			.as("nothing may notice the expiry before the cleanup job touches the key")
			.isEmpty();

		fileUnderTheMinutesTheCleanupJobWillRead(saved.getId());

		this.sessions.cleanUpExpiredSessions();

		SessionExpiredEvent event = this.events.awaitEvent(SessionExpiredEvent.class, saved.getId());
		assertThat(event.<RedisSession>getSession().<String>getAttribute("user")).isEqualTo("alice");
		assertThat(store().exists(shadowKey)).isFalse();
	}

	/**
	 * Records the session in the minute buckets the cleanup job reads. Spring Session
	 * files a session under the minute it expires in, which is always still in the
	 * future, so a job run made now would look at a bucket the session is not in yet —
	 * the bucket it reads is the minute just gone. Filing the session there is what lets
	 * the job run at all rather than a minute from now, and both candidate minutes are
	 * written so that the wall clock crossing a boundary mid-test cannot decide the
	 * outcome.
	 */
	private void fileUnderTheMinutesTheCleanupJobWillRead(String sessionId) {
		RedisOperations<String, Object> redis = this.sessions.getSessionRedisOperations();
		long minute = truncateToMinute(store().currentTimeMillis());
		for (long bucket : new long[] { minute, minute + MILLIS_PER_MINUTE }) {
			redis.boundSetOps(AdapterServerTestConfiguration.EXPIRATIONS_KEY_PREFIX + bucket)
				.add("expires:" + sessionId);
		}
	}

	/**
	 * Returns the backend the application's sessions land in. The application is
	 * configured with the default database, which is the first of them.
	 * @return the backend of database 0
	 */
	private KeyValueStore store() {
		return this.databases.database(0);
	}

	private static long truncateToMinute(long epochMilli) {
		return Instant.ofEpochMilli(epochMilli).truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
	}

	private static byte[] shadowKey(String sessionId) {
		return (AdapterServerTestConfiguration.SHADOW_KEY_PREFIX + sessionId).getBytes(UTF_8);
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
