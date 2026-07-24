package am.ik.redis.adapter.command;

import java.io.IOException;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.pubsub.PubSubRegistry;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;

class PubSubCommandsTest {

	private static final String CREATED_PATTERN = "spring:session:event:0:created:*";

	private static final String CREATED_CHANNEL = "spring:session:event:0:created:abc";

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final PubSubRegistry registry = new PubSubRegistry();

	private final TestCommandContext context = new TestCommandContext().pubSub(this.registry);

	private final TestCommandContext publisher = new TestCommandContext().pubSub(this.registry);

	@Test
	void subscribeConfirmsEachChannelWithTheRunningCount() throws Exception {
		dispatch("SUBSCRIBE", "__keyevent@0__:del", "__keyevent@0__:expired");

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$9\r
				subscribe\r
				$18\r
				__keyevent@0__:del\r
				:1\r
				*3\r
				$9\r
				subscribe\r
				$22\r
				__keyevent@0__:expired\r
				:2\r
				""");
	}

	@Test
	void psubscribeConfirmsEachPattern() throws Exception {
		dispatch("PSUBSCRIBE", CREATED_PATTERN);

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$10\r
				psubscribe\r
				$32\r
				spring:session:event:0:created:*\r
				:1\r
				""");
	}

	/**
	 * A RESP3 client is told about a subscription with a push frame, exactly as Redis
	 * tells it; the RESP2 array above and this push carry the same three elements.
	 */
	@Test
	void aResp3ConnectionIsConfirmedWithAPushFrame() throws Exception {
		this.context.protocolVersion(RespVersion.RESP3);

		dispatch("SUBSCRIBE", "news");

		assertThat(this.context.replies()).isEqualTo("""
				>3\r
				$9\r
				subscribe\r
				$4\r
				news\r
				:1\r
				""");
	}

	@Test
	void publishReportsHowManyConnectionsReceivedTheMessage() throws Exception {
		dispatch("PSUBSCRIBE", CREATED_PATTERN);
		this.context.reset();

		this.dispatcher.dispatch(this.publisher, argv("PUBLISH", CREATED_CHANNEL, "delta"));

		assertThat(this.publisher.replies()).isEqualTo(":1\r\n");
	}

	/**
	 * The subscriber and the publisher are different connections, so what proves the
	 * registry is shared is that the message shows up on the other connection's wire.
	 */
	@Test
	void aPatternSubscriberIsPushedTheChannelThatMatchedAndTheBody() throws Exception {
		dispatch("PSUBSCRIBE", CREATED_PATTERN);
		this.context.reset();

		this.dispatcher.dispatch(this.publisher, argv("PUBLISH", CREATED_CHANNEL, "delta"));

		assertThat(this.context.replies()).isEqualTo("""
				*4\r
				$8\r
				pmessage\r
				$32\r
				spring:session:event:0:created:*\r
				$34\r
				spring:session:event:0:created:abc\r
				$5\r
				delta\r
				""");
	}

	@Test
	void aChannelSubscriberIsPushedTheChannelAndTheBody() throws Exception {
		dispatch("SUBSCRIBE", "news");
		this.context.reset();

		this.dispatcher.dispatch(this.publisher, argv("PUBLISH", "news", "body"));

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$7\r
				message\r
				$4\r
				news\r
				$4\r
				body\r
				""");
	}

	@Test
	void publishingWithNoSubscriberReachesNobody() throws Exception {
		dispatch("PUBLISH", "news", "body");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void unsubscribeConfirmsEachChannelAndCountsDown() throws Exception {
		dispatch("SUBSCRIBE", "a", "b");
		this.context.reset();

		dispatch("UNSUBSCRIBE", "a");

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$11\r
				unsubscribe\r
				$1\r
				a\r
				:1\r
				""");
	}

	@Test
	void unsubscribeWithoutArgumentsDropsEveryChannel() throws Exception {
		dispatch("SUBSCRIBE", "a", "b");
		this.context.reset();

		dispatch("UNSUBSCRIBE");

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$11\r
				unsubscribe\r
				$1\r
				a\r
				:1\r
				*3\r
				$11\r
				unsubscribe\r
				$1\r
				b\r
				:0\r
				""");
	}

	/**
	 * Redis answers a connection that unsubscribes from nothing with a single
	 * confirmation carrying no channel name, and clients rely on getting exactly one
	 * frame back.
	 */
	@Test
	void unsubscribingWhenNotSubscribedIsConfirmedWithoutAChannel() throws Exception {
		dispatch("UNSUBSCRIBE");

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$11\r
				unsubscribe\r
				$-1\r
				:0\r
				""");
	}

	@Test
	void punsubscribeWithoutArgumentsDropsEveryPattern() throws Exception {
		dispatch("PSUBSCRIBE", CREATED_PATTERN);
		this.context.reset();

		dispatch("PUNSUBSCRIBE");

		assertThat(this.context.replies()).isEqualTo("""
				*3\r
				$12\r
				punsubscribe\r
				$32\r
				spring:session:event:0:created:*\r
				:0\r
				""");
	}

	@Test
	void unsubscribingStopsTheDelivery() throws Exception {
		dispatch("SUBSCRIBE", "news");
		dispatch("UNSUBSCRIBE", "news");
		this.context.reset();

		this.dispatcher.dispatch(this.publisher, argv("PUBLISH", "news", "body"));

		assertThat(this.context.replies()).isEmpty();
		assertThat(this.publisher.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void aSubscribedConnectionStillAnswersPing() throws Exception {
		dispatch("SUBSCRIBE", "news");
		this.context.reset();

		dispatch("PING");

		assertThat(this.context.replies()).isEqualTo("+PONG\r\n");
	}

	/**
	 * Redis puts a subscribed connection into a mode where only the pub/sub commands are
	 * accepted; the adapter does the same, so a client cannot mix session traffic into a
	 * connection whose output stream is carrying pushes.
	 */
	@Test
	void aSubscribedConnectionRefusesEveryOtherCommand() throws Exception {
		dispatch("SUBSCRIBE", "news");
		this.context.reset();

		dispatch("HGETALL", "spring:session:sessions:abc");

		assertThat(this.context.replies()).isEqualTo("-ERR Can't execute 'hgetall': only (P|S)SUBSCRIBE / "
				+ "(P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context\r\n");
	}

	@Test
	void aConnectionThatUnsubscribedFromEverythingAcceptsCommandsAgain() throws Exception {
		dispatch("SUBSCRIBE", "news");
		dispatch("UNSUBSCRIBE");
		this.context.reset();

		dispatch("PUBLISH", "news", "body");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void subscribeNeedsAtLeastOneChannel() throws Exception {
		dispatch("SUBSCRIBE");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'subscribe' command\r\n");
	}

	@Test
	void psubscribeNeedsAtLeastOnePattern() throws Exception {
		dispatch("PSUBSCRIBE");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'psubscribe' command\r\n");
	}

	@Test
	void publishTakesAChannelAndABody() throws Exception {
		dispatch("PUBLISH", "news");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'publish' command\r\n");
	}

	private void dispatch(String... arguments) throws IOException {
		this.dispatcher.dispatch(this.context, argv(arguments));
	}

}
