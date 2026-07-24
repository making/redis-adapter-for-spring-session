package am.ik.redis.adapter.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the key-level commands Spring Session drives a session's lifetime with:
 * existence checks, deletion, the id change, and the TTL family. Commands are driven
 * through the dispatcher, so the assertions cover the exact reply bytes a client
 * receives.
 */
class KeyCommandsTest {

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final TestCommandContext context = new TestCommandContext().store(this.store);

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	@Test
	void existsCountsEveryKeyItIsGivenIncludingRepeats() throws Exception {
		put("a", "b");

		this.dispatcher.dispatch(this.context, argv("EXISTS", "a"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "a", "b", "missing"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "a", "a"));
		this.dispatcher.dispatch(this.context, argv("EXISTS", "missing"));

		assertThat(this.context.replies()).isEqualTo(":1\r\n:2\r\n:2\r\n:0\r\n");
	}

	/**
	 * The background cleanup touches a key with {@code EXISTS} precisely to force lazy
	 * expiry, so the check has to observe the elapsed TTL rather than the stale entry.
	 */
	@Test
	void existsExpiresAKeyWhoseDeadlineHasPassed() throws Exception {
		List<String> expired = recordExpiredKeys();
		put("a");
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "a", deadlineIn(1_000)));
		this.context.reset();
		this.store.advance(1_000);

		this.dispatcher.dispatch(this.context, argv("EXISTS", "a"));

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
		assertThat(expired).containsExactly("a");
	}

	@Test
	void delRemovesTheKeysItFindsAndCountsThem() throws Exception {
		put("a", "b");

		this.dispatcher.dispatch(this.context, argv("DEL", "a", "b", "missing"));
		this.dispatcher.dispatch(this.context, argv("DEL", "a"));

		assertThat(this.context.replies()).isEqualTo(":2\r\n:0\r\n");
		assertThat(this.store.exists("a".getBytes(UTF_8))).isFalse();
	}

	@Test
	void unlinkRemovesKeysJustLikeDel() throws Exception {
		put("a");

		this.dispatcher.dispatch(this.context, argv("UNLINK", "a"));

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
		assertThat(this.store.exists("a".getBytes(UTF_8))).isFalse();
	}

	@Test
	void renameMovesTheValueAndItsExpiryToTheNewKey() throws Exception {
		put("old");
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "old", deadlineIn(5_000)));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("RENAME", "old", "new"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
		assertThat(this.store.exists("old".getBytes(UTF_8))).isFalse();
		assertThat(fieldOf("new")).isEqualTo("value");
		assertThat(this.store.getExpireAt("new".getBytes(UTF_8))).isEqualTo(this.store.currentTimeMillis() + 5_000);
	}

	/**
	 * Spring Session swallows exactly this message when it renames a session that is
	 * already gone, and rethrows anything else, so the prefix is part of the contract.
	 */
	@Test
	void renamingAKeyThatDoesNotExistFailsWithNoSuchKey() throws Exception {
		this.dispatcher.dispatch(this.context, argv("RENAME", "missing", "new"));

		assertThat(this.context.replies()).isEqualTo("-ERR no such key\r\n");
	}

	@Test
	void renameOverwritesAnExistingDestination() throws Exception {
		put("old");
		this.store.hset("new".getBytes(UTF_8), Map.of("field".getBytes(UTF_8), "other".getBytes(UTF_8)));

		this.dispatcher.dispatch(this.context, argv("RENAME", "old", "new"));

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
		assertThat(fieldOf("new")).isEqualTo("value");
	}

	@Test
	void pexpireatTakesAnAbsoluteDeadlineInMilliseconds() throws Exception {
		put("a");

		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "a", deadlineIn(1_500)));
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "missing", deadlineIn(1_500)));

		assertThat(this.context.replies()).isEqualTo(":1\r\n:0\r\n");
		assertThat(this.store.getExpireAt("a".getBytes(UTF_8))).isEqualTo(this.store.currentTimeMillis() + 1_500);
	}

	@Test
	void expireatTakesAnAbsoluteDeadlineInSeconds() throws Exception {
		put("a");

		this.dispatcher.dispatch(this.context, argv("EXPIREAT", "a", Long.toString(1_700_000_000L)));

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
		assertThat(this.store.getExpireAt("a".getBytes(UTF_8))).isEqualTo(1_700_000_000_000L);
	}

	@Test
	void expireAndPexpireCountFromNow() throws Exception {
		put("a", "b");
		long now = this.store.currentTimeMillis();

		this.dispatcher.dispatch(this.context, argv("EXPIRE", "a", "30"));
		this.dispatcher.dispatch(this.context, argv("PEXPIRE", "b", "1500"));

		assertThat(this.context.replies()).isEqualTo(":1\r\n:1\r\n");
		assertThat(this.store.getExpireAt("a".getBytes(UTF_8))).isEqualTo(now + 30_000);
		assertThat(this.store.getExpireAt("b".getBytes(UTF_8))).isEqualTo(now + 1_500);
	}

	@Test
	void persistClearsTheExpiryOnlyWhenThereIsOne() throws Exception {
		put("a", "b");
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "a", deadlineIn(1_000)));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("PERSIST", "a"));
		this.dispatcher.dispatch(this.context, argv("PERSIST", "b"));
		this.dispatcher.dispatch(this.context, argv("PERSIST", "missing"));

		assertThat(this.context.replies()).isEqualTo(":1\r\n:0\r\n:0\r\n");
		assertThat(this.store.getExpireAt("a".getBytes(UTF_8))).isNull();
	}

	@Test
	void ttlAndPttlReportTheTimeLeftAndWhyThereIsNone() throws Exception {
		put("a", "b");
		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "a", deadlineIn(1_500)));
		this.context.reset();

		this.dispatcher.dispatch(this.context, argv("PTTL", "a"));
		this.dispatcher.dispatch(this.context, argv("TTL", "a"));
		this.dispatcher.dispatch(this.context, argv("PTTL", "b"));
		this.dispatcher.dispatch(this.context, argv("TTL", "missing"));

		assertThat(this.context.replies()).isEqualTo(":1500\r\n:2\r\n:-1\r\n:-2\r\n");
	}

	@Test
	void typeNamesWhatAKeyHolds() throws Exception {
		put("hash");
		this.store.append("text".getBytes(UTF_8), "value".getBytes(UTF_8));
		this.store.sadd("members".getBytes(UTF_8), List.of("one".getBytes(UTF_8)));

		this.dispatcher.dispatch(this.context, argv("TYPE", "hash"));
		this.dispatcher.dispatch(this.context, argv("TYPE", "text"));
		this.dispatcher.dispatch(this.context, argv("TYPE", "members"));
		this.dispatcher.dispatch(this.context, argv("TYPE", "missing"));

		assertThat(this.context.replies()).isEqualTo("+hash\r\n+string\r\n+set\r\n+none\r\n");
	}

	@Test
	void aDeadlineThatIsNotANumberIsRejected() throws Exception {
		put("a");

		this.dispatcher.dispatch(this.context, argv("PEXPIREAT", "a", "soon"));
		this.dispatcher.dispatch(this.context, argv("RENAME", "a"));

		assertThat(this.context.replies()).isEqualTo("-ERR value is not an integer or out of range\r\n"
				+ "-ERR wrong number of arguments for 'rename' command\r\n");
	}

	private void put(String... keys) {
		for (String key : keys) {
			this.store.hset(key.getBytes(UTF_8), Map.of("field".getBytes(UTF_8), "value".getBytes(UTF_8)));
		}
	}

	private String fieldOf(String key) {
		HashValue hash = (HashValue) Objects.requireNonNull(this.store.get(key.getBytes(UTF_8)));
		return new String(hash.fields().values().iterator().next(), UTF_8);
	}

	private String deadlineIn(long millis) {
		return Long.toString(this.store.currentTimeMillis() + millis);
	}

	private List<String> recordExpiredKeys() {
		List<String> expired = new ArrayList<>();
		this.store.addKeyEventListener(new KeyEventListener() {
			@Override
			public void onExpired(byte[] key) {
				expired.add(new String(key, UTF_8));
			}
		});
		return expired;
	}

}
