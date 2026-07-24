package am.ik.redis.adapter.pubsub;

import java.nio.charset.StandardCharsets;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notifier is what turns a backend's key removals into the {@code del} and
 * {@code expired} keyspace notifications Spring Session's session-deleted and
 * session-expired events are built on, so these tests drive a real backend rather than
 * calling the listener by hand.
 */
class KeyspaceNotifierTest {

	private static final String SHADOW_KEY = "spring:session:sessions:expires:abc";

	private final PubSubRegistry registry = new PubSubRegistry();

	private final RecordingSubscriber subscriber = new RecordingSubscriber();

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	KeyspaceNotifierTest() {
		this.store.addKeyEventListener(new KeyspaceNotifier(this.registry, 0));
	}

	@Test
	void deletingALiveKeyPublishesTheKeyNameOnTheDelChannel() {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:del"));
		this.store.append(bytes(SHADOW_KEY), new byte[0]);

		this.store.delete(bytes(SHADOW_KEY));

		assertThat(this.subscriber.poll()).isEqualTo("message __keyevent@0__:del " + SHADOW_KEY);
	}

	@Test
	void deletingAKeyThatIsNotThereNotifiesNobody() {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:del"));

		this.store.delete(bytes(SHADOW_KEY));

		assertThat(this.subscriber.poll()).isEqualTo("<none>");
	}

	/**
	 * The touch is what Spring Session's background cleanup does to force a lazy expiry,
	 * so the notification has to be emitted at the moment of that access.
	 */
	@Test
	void aKeyExpiringWhenItIsTouchedPublishesTheKeyNameOnTheExpiredChannel() {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:expired"));
		this.store.append(bytes(SHADOW_KEY), new byte[0]);
		this.store.expireAt(bytes(SHADOW_KEY), this.store.currentTimeMillis() + 1000);
		this.store.advance(1001);

		assertThat(this.store.exists(bytes(SHADOW_KEY))).isFalse();

		assertThat(this.subscriber.poll()).isEqualTo("message __keyevent@0__:expired " + SHADOW_KEY);
	}

	@Test
	void anExpiredKeyIsReportedAsExpiredRatherThanDeletedEvenWhenItIsDeleted() {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:expired"));
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:del"));
		this.store.append(bytes(SHADOW_KEY), new byte[0]);
		this.store.expireAt(bytes(SHADOW_KEY), this.store.currentTimeMillis() + 1000);
		this.store.advance(1001);

		this.store.delete(bytes(SHADOW_KEY));

		assertThat(this.subscriber.poll()).isEqualTo("message __keyevent@0__:expired " + SHADOW_KEY);
		assertThat(this.subscriber.poll()).isEqualTo("<none>");
	}

	/**
	 * The database index is part of the channel name, so a backend serving database 1
	 * must not publish onto database 0's channel.
	 */
	@Test
	void theChannelNameCarriesTheDatabaseTheKeyLivesIn() {
		FakeKeyValueStore database1 = new FakeKeyValueStore();
		database1.addKeyEventListener(new KeyspaceNotifier(this.registry, 1));
		this.registry.subscribe(this.subscriber, bytes("__keyevent@1__:del"));
		database1.append(bytes(SHADOW_KEY), new byte[0]);

		database1.delete(bytes(SHADOW_KEY));

		assertThat(this.subscriber.poll()).isEqualTo("message __keyevent@1__:del " + SHADOW_KEY);
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

}
