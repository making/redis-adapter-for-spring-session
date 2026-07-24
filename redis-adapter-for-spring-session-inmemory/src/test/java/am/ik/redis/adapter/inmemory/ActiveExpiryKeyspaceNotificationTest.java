package am.ik.redis.adapter.inmemory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import am.ik.redis.adapter.pubsub.KeyspaceNotifier;
import am.ik.redis.adapter.pubsub.PubSubRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Session's session-expired event depends on the notification arriving even for a
 * session nobody ever asks about again, which is what the backend's active-expiry sweeper
 * is for. Passive expiry is covered by the core tests; this one proves the sweeper
 * reaches the same emitter.
 */
class ActiveExpiryKeyspaceNotificationTest {

	private static final String SHADOW_KEY = "spring:session:sessions:expires:abc";

	@Test
	void theSweeperPublishesAnExpiredKeyeventForAKeyNobodyTouches() throws Exception {
		PubSubRegistry registry = new PubSubRegistry();
		RecordingSubscriber subscriber = new RecordingSubscriber();
		registry.subscribe(subscriber, bytes("__keyevent@0__:expired"));
		try (InMemoryKeyValueStore store = InMemoryKeyValueStore.builder()
			.sweepInterval(Duration.ofMillis(20))
			.build()) {
			store.addKeyEventListener(new KeyspaceNotifier(registry, 0));
			store.append(bytes(SHADOW_KEY), new byte[0]);
			store.expireAt(bytes(SHADOW_KEY), store.currentTimeMillis() + 20);

			assertThat(subscriber.take()).isEqualTo("message __keyevent@0__:expired " + SHADOW_KEY);
		}
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

}
