package am.ik.redis.adapter.pubsub;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubRegistryTest {

	private final PubSubRegistry registry = new PubSubRegistry();

	private final RecordingSubscriber subscriber = new RecordingSubscriber();

	private final RecordingSubscriber otherSubscriber = new RecordingSubscriber();

	@Test
	void aChannelSubscriberReceivesWhatAnotherConnectionPublishes() throws Exception {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:del"));

		int receivers = this.registry.publish(bytes("__keyevent@0__:del"), bytes("spring:session:sessions:expires:a"));

		assertThat(receivers).isEqualTo(1);
		assertThat(this.subscriber.take()).isEqualTo("message __keyevent@0__:del spring:session:sessions:expires:a");
	}

	@Test
	void aPublicationWithNoSubscriberReachesNobodyAndIsNotAnError() {
		assertThat(this.registry.publish(bytes("__keyevent@0__:del"), bytes("key"))).isZero();
	}

	@Test
	void aChannelSubscriberIsNotReachedByAnotherChannel() {
		this.registry.subscribe(this.subscriber, bytes("__keyevent@0__:del"));

		int receivers = this.registry.publish(bytes("__keyevent@0__:expired"), bytes("key"));

		assertThat(receivers).isZero();
		assertThat(this.subscriber.poll()).isEqualTo("<none>");
	}

	@Test
	void everySubscriberOfAChannelIsCounted() {
		this.registry.subscribe(this.subscriber, bytes("news"));
		this.registry.subscribe(this.otherSubscriber, bytes("news"));

		assertThat(this.registry.publish(bytes("news"), bytes("body"))).isEqualTo(2);
	}

	@Test
	void aPatternSubscriberReceivesTheConcreteChannelAndThePatternThatMatchedIt() throws Exception {
		this.registry.psubscribe(this.subscriber, bytes("spring:session:event:0:created:*"));

		int receivers = this.registry.publish(bytes("spring:session:event:0:created:abc"), bytes("delta"));

		assertThat(receivers).isEqualTo(1);
		assertThat(this.subscriber.take())
			.isEqualTo("pmessage spring:session:event:0:created:* spring:session:event:0:created:abc delta");
	}

	@Test
	void aPatternSubscriberIsNotReachedByAChannelItsPatternDoesNotMatch() {
		this.registry.psubscribe(this.subscriber, bytes("spring:session:event:0:created:*"));

		assertThat(this.registry.publish(bytes("spring:session:event:0:other:abc"), bytes("delta"))).isZero();
	}

	@Test
	void aChannelAndAPatternSubscriberBothReceiveTheSamePublication() {
		this.registry.subscribe(this.subscriber, bytes("news:sport"));
		this.registry.psubscribe(this.otherSubscriber, bytes("news:*"));

		assertThat(this.registry.publish(bytes("news:sport"), bytes("body"))).isEqualTo(2);
	}

	@Test
	void subscribingCountsEveryChannelAndPatternOfTheConnection() {
		assertThat(this.registry.subscribe(this.subscriber, bytes("a"))).isEqualTo(1);
		assertThat(this.registry.subscribe(this.subscriber, bytes("b"))).isEqualTo(2);
		assertThat(this.registry.psubscribe(this.subscriber, bytes("c*"))).isEqualTo(3);
		assertThat(this.registry.subscriptionCount(this.subscriber)).isEqualTo(3);
		assertThat(this.registry.subscriptionCount(this.otherSubscriber)).isZero();
	}

	@Test
	void subscribingTwiceToTheSameChannelDoesNotCountTwice() {
		this.registry.subscribe(this.subscriber, bytes("a"));

		assertThat(this.registry.subscribe(this.subscriber, bytes("a"))).isEqualTo(1);
		assertThat(this.registry.publish(bytes("a"), bytes("body"))).isEqualTo(1);
	}

	@Test
	void unsubscribingStopsDeliveryAndCountsDown() {
		this.registry.subscribe(this.subscriber, bytes("a"));
		this.registry.subscribe(this.subscriber, bytes("b"));

		assertThat(this.registry.unsubscribe(this.subscriber, bytes("a"))).isEqualTo(1);
		assertThat(this.registry.publish(bytes("a"), bytes("body"))).isZero();
		assertThat(this.registry.publish(bytes("b"), bytes("body"))).isEqualTo(1);
	}

	@Test
	void unsubscribingFromAChannelTheConnectionNeverSubscribedToIsHarmless() {
		this.registry.subscribe(this.subscriber, bytes("a"));

		assertThat(this.registry.unsubscribe(this.subscriber, bytes("zzz"))).isEqualTo(1);
	}

	@Test
	void unsubscribingAPatternLeavesTheChannelsAlone() {
		this.registry.subscribe(this.subscriber, bytes("news:sport"));
		this.registry.psubscribe(this.subscriber, bytes("news:*"));

		assertThat(this.registry.punsubscribe(this.subscriber, bytes("news:*"))).isEqualTo(1);
		assertThat(this.registry.publish(bytes("news:sport"), bytes("body"))).isEqualTo(1);
	}

	@Test
	void theSubscriptionsOfAConnectionAreReportedInSubscriptionOrder() {
		this.registry.subscribe(this.subscriber, bytes("b"));
		this.registry.subscribe(this.subscriber, bytes("a"));
		this.registry.psubscribe(this.subscriber, bytes("p*"));

		assertThat(text(this.registry.channelsOf(this.subscriber))).containsExactly("b", "a");
		assertThat(text(this.registry.patternsOf(this.subscriber))).containsExactly("p*");
		assertThat(text(this.registry.channelsOf(this.otherSubscriber))).isEmpty();
	}

	@Test
	void aConnectionIsSubscribedUntilItsLastSubscriptionGoes() {
		assertThat(this.registry.isSubscribed(this.subscriber)).isFalse();

		this.registry.subscribe(this.subscriber, bytes("a"));
		assertThat(this.registry.isSubscribed(this.subscriber)).isTrue();

		this.registry.unsubscribe(this.subscriber, bytes("a"));
		assertThat(this.registry.isSubscribed(this.subscriber)).isFalse();
	}

	/**
	 * A closed connection has to leave no trace behind, otherwise a publication would try
	 * to write to a dead socket for the lifetime of the server.
	 */
	@Test
	void removingAConnectionDropsEveryChannelAndPatternItHeld() {
		this.registry.subscribe(this.subscriber, bytes("a"));
		this.registry.psubscribe(this.subscriber, bytes("a*"));
		this.registry.subscribe(this.otherSubscriber, bytes("a"));

		this.registry.unsubscribeAll(this.subscriber);

		assertThat(this.registry.isSubscribed(this.subscriber)).isFalse();
		assertThat(this.registry.subscriptionCount(this.subscriber)).isZero();
		assertThat(this.registry.publish(bytes("a"), bytes("body"))).isEqualTo(1);
	}

	@Test
	void aSubscriberThatFailsDoesNotStopTheOthersFromReceiving() {
		this.registry.subscribe(new FailingSubscriber(), bytes("a"));
		this.registry.subscribe(this.subscriber, bytes("a"));

		assertThat(this.registry.publish(bytes("a"), bytes("body"))).isEqualTo(2);
		assertThat(this.subscriber.poll()).isEqualTo("message a body");
	}

	private static List<String> text(List<byte[]> values) {
		return values.stream().map(value -> new String(value, StandardCharsets.UTF_8)).toList();
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A subscriber whose delivery always fails, standing in for a connection whose socket
	 * died between the subscription and the publication.
	 */
	private static final class FailingSubscriber implements Subscriber {

		@Override
		public void message(byte[] channel, byte[] body) {
			throw new IllegalStateException("this subscriber is gone");
		}

		@Override
		public void patternMessage(byte[] pattern, byte[] channel, byte[] body) {
			throw new IllegalStateException("this subscriber is gone");
		}

	}

}
