package am.ik.redis.adapter.command;

import java.util.Map;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code APPEND}, which Spring Session uses to bring a key into existence rather
 * than to build up text.
 */
class StringCommandsTest {

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final TestCommandContext context = new TestCommandContext().store(this.store);

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	/**
	 * The shadow key that carries a session's expiry is created exactly like this, so an
	 * empty append has to leave behind a key that exists and can take a TTL.
	 */
	@Test
	void appendingNothingToAMissingKeyCreatesAnEmptyStringKey() throws Exception {
		this.dispatcher.dispatch(this.context, argv("APPEND", "expires", ""));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "expires"));
		this.dispatcher.dispatch(this.context, argv("TYPE", "expires"));
		this.dispatcher.dispatch(this.context,
				argv("PEXPIREAT", "expires", Long.toString(this.store.currentTimeMillis() + 1_000)));

		assertThat(this.context.replies()).isEqualTo(":0\r\n:1\r\n+string\r\n:1\r\n");
	}

	@Test
	void appendingToAnExistingStringReturnsTheNewLength() throws Exception {
		this.dispatcher.dispatch(this.context, argv("APPEND", "text", "abc"));
		this.dispatcher.dispatch(this.context, argv("APPEND", "text", "de"));

		assertThat(this.context.replies()).isEqualTo(":3\r\n:5\r\n");
	}

	@Test
	void appendingToAKeyOfAnotherTypeIsRejectedAsAWrongType() throws Exception {
		this.store.hset("session".getBytes(UTF_8), Map.of("a".getBytes(UTF_8), "1".getBytes(UTF_8)));

		this.dispatcher.dispatch(this.context, argv("APPEND", "session", "x"));

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	@Test
	void appendWithoutAValueIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("APPEND", "text"));

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'append' command\r\n");
	}

}
