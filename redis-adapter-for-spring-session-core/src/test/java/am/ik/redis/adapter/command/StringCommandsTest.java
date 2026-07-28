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
	 * The deadline arrives with the value rather than in a command of its own, which is
	 * the whole point of the option: there is no moment at which the key is there without
	 * it.
	 */
	@Test
	void settingWithSecondsGivesTheKeyItsDeadlineStraightAway() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "10"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));
		this.dispatcher.dispatch(this.context, argv("GET", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:10000\r\n$5\r\nhello\r\n");
	}

	@Test
	void settingWithMillisecondsGivesTheKeyItsDeadlineStraightAway() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "PX", "1500"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:1500\r\n");
	}

	/** {@code EXAT} counts from the epoch, so the store's clock is not added to it. */
	@Test
	void settingWithAnAbsoluteSecondGivesTheKeyThatDeadline() throws Exception {
		long deadline = this.store.currentTimeMillis() + 30_000;

		this.dispatcher.dispatch(this.context,
				argv("SET", "greeting", "hello", "EXAT", Long.toString(deadline / 1000)));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:30000\r\n");
	}

	@Test
	void settingWithAnAbsoluteMillisecondGivesTheKeyThatDeadline() throws Exception {
		long deadline = this.store.currentTimeMillis() + 45_000;

		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "PXAT", Long.toString(deadline)));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:45000\r\n");
	}

	/** An option is a name on the wire, and Redis reads it however it is spelled. */
	@Test
	void anOptionIsReadWhicheverCaseItArrivesIn() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "ex", "10"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:10000\r\n");
	}

	/**
	 * A deadline in the past is written like any other, and the key is gone the moment
	 * anything touches it — what Redis does with {@code SET key value EXAT 1}.
	 */
	@Test
	void settingWithADeadlineAlreadyPassedLeavesNothingBehind() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "PXAT", "1"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:0\r\n");
	}

	/** The expiry replaces whatever deadline the key had, as a plain {@code SET} does. */
	@Test
	void settingWithAnExpiryReplacesTheDeadlineTheKeyHad() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "60"));

		this.dispatcher.dispatch(this.context.reset(), argv("SET", "greeting", "hi", "EX", "10"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "greeting"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n:10000\r\n");
	}

	/**
	 * Redis refuses a non-positive expiry — an absolute one included — before the key is
	 * touched, so a rejected {@code SET} leaves nothing behind.
	 */
	@Test
	void setWithANonPositiveExpiryIsRejectedAndWritesNothing() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "0"));
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "PXAT", "-1"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "greeting"));

		assertThat(this.context.replies()).isEqualTo("-ERR invalid expire time in 'set' command\r\n"
				+ "-ERR invalid expire time in 'set' command\r\n:0\r\n");
	}

	/** Seconds far enough out to overflow milliseconds are not a deadline either. */
	@Test
	void setWithAnExpiryThatDoesNotFitIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", Long.toString(Long.MAX_VALUE)));

		assertThat(this.context.replies()).isEqualTo("-ERR invalid expire time in 'set' command\r\n");
	}

	@Test
	void setWithAnExpiryThatIsNotANumberIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "soon"));

		assertThat(this.context.replies()).isEqualTo("-ERR value is not an integer or out of range\r\n");
	}

	@Test
	void setWithAnExpiryOptionAndNoArgumentIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX"));

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n");
	}

	/** One deadline or none: Redis refuses two ways of spelling the same thing. */
	@Test
	void setWithTwoExpiriesIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "EX", "10", "PX", "1000"));

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n");
	}

	/**
	 * A conditional write is refused rather than ignored: a client told {@code +OK} would
	 * believe the key it asked not to overwrite is untouched, and nothing would ever tell
	 * it otherwise.
	 */
	@Test
	void setWithAnOptionTheStoreCannotExpressIsRejectedRatherThanIgnored() throws Exception {
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "NX"));
		this.dispatcher.dispatch(this.context, argv("SET", "greeting", "hello", "KEEPTTL"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "greeting"));

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n-ERR syntax error\r\n:0\r\n");
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
