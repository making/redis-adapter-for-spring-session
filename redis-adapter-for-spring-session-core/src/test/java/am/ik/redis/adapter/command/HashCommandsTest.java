package am.ik.redis.adapter.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.store.FakeKeyValueStore;
import am.ik.redis.adapter.store.HashValue;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Verifies the hash commands Spring Session's session hash is read and written with.
 * Commands are driven through the dispatcher, so the assertions cover the exact reply
 * bytes a client receives, including the errors the dispatcher formats.
 */
class HashCommandsTest {

	private static final String WRONG_TYPE = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final TestCommandContext context = new TestCommandContext().store(this.store);

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	@Test
	void hmsetStoresEveryFieldAndAcknowledges() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "creationTime", "1", "maxInactive", "1800"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
		assertThat(fieldsOf("session")).containsExactly(entry("creationTime", "1"), entry("maxInactive", "1800"));
	}

	@Test
	void hsetAnswersHowManyFieldsWereNew() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HSET", "session", "a", "1", "b", "2"));
		this.dispatcher.dispatch(this.context, argv("HSET", "session", "b", "3", "c", "4"));

		assertThat(this.context.replies()).isEqualTo(":2\r\n:1\r\n");
		assertThat(fieldsOf("session")).containsExactly(entry("a", "1"), entry("b", "3"), entry("c", "4"));
	}

	@Test
	void hgetallReturnsEveryFieldInTheOrderItWasWritten() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "a", "1", "b", "2"));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("HGETALL", "session"));

		assertThat(this.context.replies()).isEqualTo("*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n");
	}

	@Test
	void hgetallIsEncodedAsAMapInResp3() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "a", "1"));
		this.context.reset();
		this.context.protocolVersion(RespVersion.RESP3);

		this.dispatcher.dispatch(this.context, argv("HGETALL", "session"));

		assertThat(this.context.replies()).isEqualTo("%1\r\n$1\r\na\r\n$1\r\n1\r\n");
	}

	/**
	 * Spring Session reads an empty hash as "this session is gone", so a missing key must
	 * answer an empty map rather than a null.
	 */
	@Test
	void hgetallOfAMissingKeyIsAnEmptyMapAndNotANull() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HGETALL", "missing"));

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	/**
	 * Removing a session attribute writes a zero-length value rather than deleting the
	 * field, so an empty value has to survive the round trip as a value.
	 */
	@Test
	void hgetallReturnsZeroLengthValuesUnchanged() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "sessionAttr:name", ""));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("HGETALL", "session"));

		assertThat(this.context.replies()).isEqualTo("*2\r\n$16\r\nsessionAttr:name\r\n$0\r\n\r\n");
	}

	@Test
	void hgetReturnsTheFieldValueOrNullWhenItIsAbsent() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "a", "1"));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("HGET", "session", "a"));
		this.dispatcher.dispatch(this.context, argv("HGET", "session", "b"));
		this.dispatcher.dispatch(this.context, argv("HGET", "missing", "a"));

		assertThat(this.context.replies()).isEqualTo("$1\r\n1\r\n$-1\r\n$-1\r\n");
	}

	@Test
	void aHashCommandAgainstAnotherTypeIsRejectedAsAWrongType() throws Exception {
		this.store.append("text".getBytes(UTF_8), "value".getBytes(UTF_8));

		this.dispatcher.dispatch(this.context, argv("HGETALL", "text"));
		this.dispatcher.dispatch(this.context, argv("HGET", "text", "a"));
		this.dispatcher.dispatch(this.context, argv("HMSET", "text", "a", "1"));

		assertThat(this.context.replies()).isEqualTo(WRONG_TYPE.repeat(3));
	}

	@Test
	void aFieldWithoutAValueIsRejected() throws Exception {
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "a", "1", "b"));
		this.dispatcher.dispatch(this.context, argv("HSET", "session"));

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'hmset' command\r\n"
				+ "-ERR wrong number of arguments for 'hset' command\r\n");
	}

	@Test
	void writingToAKeyLeavesItsExpiryAlone() throws Exception {
		long deadline = this.store.currentTimeMillis() + 5_000;
		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "a", "1"));
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "session", Long.toString(deadline)));

		this.dispatcher.dispatch(this.context, argv("HMSET", "session", "b", "2"));

		assertThat(this.store.getExpireAt("session".getBytes(UTF_8))).isEqualTo(deadline);
	}

	private Map<String, String> fieldsOf(String key) {
		HashValue hash = (HashValue) Objects.requireNonNull(this.store.get(key.getBytes(UTF_8)));
		Map<String, String> fields = new LinkedHashMap<>();
		hash.fields()
			.forEach((field, value) -> fields.put(new String(field.asBytes(), UTF_8), new String(value, UTF_8)));
		return fields;
	}

}
