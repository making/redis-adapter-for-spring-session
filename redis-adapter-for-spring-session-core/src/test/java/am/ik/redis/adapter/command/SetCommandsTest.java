package am.ik.redis.adapter.command;

import java.io.IOException;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The set commands carry the principal index and the expiration buckets of indexed mode.
 * Members are opaque bytes, so these tests only ever assert on what comes back on the
 * wire.
 */
class SetCommandsTest {

	private static final String INDEX_KEY = "spring:session:index:PRINCIPAL_NAME_INDEX_NAME:alice";

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final TestCommandContext context = new TestCommandContext().store(this.store);

	@Test
	void saddAnswersHowManyMembersWereNew() throws Exception {
		dispatch("SADD", INDEX_KEY, "one", "two");

		assertThat(this.context.replies()).isEqualTo(":2\r\n");
	}

	@Test
	void saddIgnoresMembersThatAreAlreadyThere() throws Exception {
		dispatch("SADD", INDEX_KEY, "one", "two");
		this.context.reset();

		dispatch("SADD", INDEX_KEY, "two", "three");

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
	}

	@Test
	void smembersAnswersEveryMember() throws Exception {
		dispatch("SADD", INDEX_KEY, "one", "two");
		this.context.reset();

		dispatch("SMEMBERS", INDEX_KEY);

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\none\r\n$3\r\ntwo\r\n");
	}

	/**
	 * A principal that has no sessions is an absent key, and Spring Session reads it as
	 * an empty index rather than as a failure.
	 */
	@Test
	void smembersOfAnAbsentKeyIsEmptyRatherThanNull() throws Exception {
		dispatch("SMEMBERS", INDEX_KEY);

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void sremAnswersHowManyMembersWereRemoved() throws Exception {
		dispatch("SADD", INDEX_KEY, "one", "two");
		this.context.reset();

		dispatch("SREM", INDEX_KEY, "two", "three");

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
	}

	@Test
	void sremOfAnAbsentKeyRemovesNothing() throws Exception {
		dispatch("SREM", INDEX_KEY, "one");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void aSetThatLosesItsLastMemberIsGone() throws Exception {
		dispatch("SADD", INDEX_KEY, "one");
		dispatch("SREM", INDEX_KEY, "one");
		this.context.reset();

		dispatch("EXISTS", INDEX_KEY);

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void membersAreComparedByValueRatherThanIdentity() throws Exception {
		dispatch("SADD", INDEX_KEY, "one");
		this.context.reset();

		dispatch("SADD", INDEX_KEY, "one");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void aSetCommandAgainstAKeyHoldingSomethingElseIsAWrongTypeError() throws Exception {
		dispatch("HSET", INDEX_KEY, "field", "value");
		this.context.reset();

		dispatch("SADD", INDEX_KEY, "one");

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	@Test
	void saddNeedsAtLeastOneMember() throws Exception {
		dispatch("SADD", INDEX_KEY);

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'sadd' command\r\n");
	}

	@Test
	void sremNeedsAtLeastOneMember() throws Exception {
		dispatch("SREM", INDEX_KEY);

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'srem' command\r\n");
	}

	@Test
	void smembersTakesExactlyOneKey() throws Exception {
		dispatch("SMEMBERS", INDEX_KEY, "extra");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'smembers' command\r\n");
	}

	private void dispatch(String... arguments) throws IOException {
		this.dispatcher.dispatch(this.context, argv(arguments));
	}

}
