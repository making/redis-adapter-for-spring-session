package am.ik.redis.adapter.command;

import java.util.Map;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code APPEND}, which Spring Session uses to bring a key into existence rather
 * than to build up text, and {@code SET} / {@code GET}, which it never sends and which
 * exist so that the server can be tried out by hand.
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

	/**
	 * The whole reason these two commands exist: a value put in by hand comes back out.
	 */
	@Test
	void whatSetPutsInIsWhatGetGivesBack() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello"));
		this.dispatcher.dispatch(this.context, argv("GET", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n$5\r\nhello\r\n");
	}

	@Test
	void gettingAMissingKeyRepliesWithANull() throws Exception {
		this.dispatcher.dispatch(this.context, argv("GET", "absent"));

		assertThat(this.context.replies()).isEqualTo("$-1\r\n");
	}

	@Test
	void settingAKeyAgainReplacesTheValue() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello"));
		this.dispatcher.dispatch(this.context.reset(), argv("SET", "greeting", "hi"));
		this.dispatcher.dispatch(this.context, argv("GET", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n$2\r\nhi\r\n");
	}

	/**
	 * Redis replaces a key whatever it held, so a session hash is a string afterwards and
	 * not a {@code WRONGTYPE}.
	 */
	@Test
	void settingAKeyThatHoldsAnotherTypeReplacesIt() throws Exception {
		this.store.hset("session".getBytes(UTF_8), Map.of("a".getBytes(UTF_8), "1".getBytes(UTF_8)));

		this.dispatcher.dispatch(this.context, argv("SET", "session", "plain"));
		this.dispatcher.dispatch(this.context, argv("TYPE", "session"));
		this.dispatcher.dispatch(this.context, argv("GET", "session"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n+string\r\n$5\r\nplain\r\n");
	}

	/** {@code SET} without {@code KEEPTTL} discards the deadline, as Redis does. */
	@Test
	void settingAKeyAgainDropsItsExpiry() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello"));
		this.dispatcher.dispatch(this.context,
				argv("PEXPIREAT", "greeting", Long.toString(this.store.currentTimeMillis() + 60_000)));

		this.dispatcher.dispatch(this.context.reset(), argv("SET", "greeting", "hi"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:-1\r\n");
	}

	@Test
	void gettingAKeyOfAnotherTypeIsRejectedAsAWrongType() throws Exception {
		this.store.hset("session".getBytes(UTF_8), Map.of("a".getBytes(UTF_8), "1".getBytes(UTF_8)));

		this.dispatcher.dispatch(this.context, argv("GET", "session"));

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	/**
	 * An option is refused rather than ignored: a client told {@code +OK} would believe
	 * the key expires in ten seconds, and nothing would ever tell it otherwise.
	 */
	@Test
	void setWithAnOptionIsRejectedRatherThanIgnored() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "10"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "greeting"));

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n:0\r\n");
	}

	@Test
	void setWithoutAValueIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting"));

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'set' command\r\n");
	}

	@Test
	void getWithoutAKeyIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("GET"));

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'get' command\r\n");
	}

}
