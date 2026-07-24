package am.ik.redis.adapter.command;

import java.io.IOException;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sorted-set commands carry the alternative expiration store, which keeps every live
 * session in one sorted set scored by the moment it is due to expire, instead of in a set
 * per minute. Members are opaque bytes, so these tests only ever assert on what comes
 * back on the wire.
 */
class ZSetCommandsTest {

	private static final String EXPIRATIONS_KEY = "spring:session:sessions:expirations";

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final TestCommandContext context = new TestCommandContext().store(this.store);

	@Test
	void zaddAnswersHowManyMembersWereNew() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");

		assertThat(this.context.replies()).isEqualTo(":2\r\n");
	}

	/**
	 * A session that is saved again is due to expire later than it was, so the store adds
	 * it a second time with a new score. That is an update of the one member rather than
	 * a second member, and Redis counts only the members it had to add.
	 */
	@Test
	void zaddMovesAMemberItAlreadyHoldsWithoutCountingIt() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one");
		this.context.reset();

		dispatch("ZADD", EXPIRATIONS_KEY, "3000", "one", "2000", "two");

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
		this.context.reset();
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "3000");
		assertThat(this.context.replies()).isEqualTo("*1\r\n$3\r\none\r\n");
	}

	/**
	 * Scores are written by the client as a {@code double}, so a session expiring at an
	 * epoch millisecond arrives in scientific notation rather than as the plain integer
	 * it conceptually is.
	 */
	@Test
	void zaddReadsTheScientificNotationAClientWrites() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1.7534567891E12", "one");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "1753456789100", "0", "WITHSCORES");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\none\r\n$15\r\n1.7534567891E12\r\n");
	}

	@Test
	void zaddRejectsAScoreThatIsNotANumber() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "soon", "one");

		assertThat(this.context.replies()).isEqualTo("-ERR value is not a valid float\r\n");
	}

	@Test
	void zaddNeedsAScoreForEveryMember() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000");

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n");
	}

	@Test
	void zaddNeedsAtLeastOneMember() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'zadd' command\r\n");
	}

	@Test
	void zremAnswersHowManyMembersWereRemoved() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREM", EXPIRATIONS_KEY, "two", "three");

		assertThat(this.context.replies()).isEqualTo(":1\r\n");
	}

	@Test
	void zremOfAnAbsentKeyRemovesNothing() throws Exception {
		dispatch("ZREM", EXPIRATIONS_KEY, "one");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void aSortedSetThatLosesItsLastMemberIsGone() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one");
		dispatch("ZREM", EXPIRATIONS_KEY, "one");
		this.context.reset();

		dispatch("EXISTS", EXPIRATIONS_KEY);

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void zremNeedsAtLeastOneMember() throws Exception {
		dispatch("ZREM", EXPIRATIONS_KEY);

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'zrem' command\r\n");
	}

	/**
	 * The query the cleanup job runs: everything due at or before now, newest first. The
	 * ends are given the other way round from the range they describe — the maximum comes
	 * first — because the reply is ordered from the maximum down.
	 */
	@Test
	void zrevrangebyscoreAnswersTheMembersOfTheRangeHighestScoreFirst() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two", "3000", "three");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "2000", "0");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\ntwo\r\n$3\r\none\r\n");
	}

	@Test
	void zrevrangebyscoreIncludesBothEndsOfTheRange() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "2000", "1000");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\ntwo\r\n$3\r\none\r\n");
	}

	@Test
	void zrevrangebyscoreExcludesAnEndWrittenWithABracket() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "(2000", "1000");

		assertThat(this.context.replies()).isEqualTo("*1\r\n$3\r\none\r\n");
	}

	/**
	 * Two sessions due in the same millisecond are ordered by their member bytes, and
	 * this reply runs from the maximum down, so the higher bytes come first.
	 */
	@Test
	void zrevrangebyscoreOrdersMembersOfEqualScoreByTheirBytes() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "bbb", "1000", "aaa");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "1000", "1000");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\nbbb\r\n$3\r\naaa\r\n");
	}

	@Test
	void zrevrangebyscoreTakesOnlyAsManyMembersAsTheLimitAllows() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two", "3000", "three");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "LIMIT", "0", "2");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$5\r\nthree\r\n$3\r\ntwo\r\n");
	}

	@Test
	void zrevrangebyscoreSkipsAsManyMembersAsTheOffsetSays() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two", "3000", "three");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "LIMIT", "2", "5");

		assertThat(this.context.replies()).isEqualTo("*1\r\n$3\r\none\r\n");
	}

	@Test
	void zrevrangebyscoreTakesEveryMemberWhenTheCountIsNegative() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "LIMIT", "0", "-1");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\ntwo\r\n$3\r\none\r\n");
	}

	@Test
	void zrevrangebyscoreOfAnAbsentKeyIsEmpty() throws Exception {
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void zrevrangebyscoreAcceptsTheInfiniteEndsOfAnUnboundedRange() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "+inf", "-inf");

		assertThat(this.context.replies()).isEqualTo("*2\r\n$3\r\ntwo\r\n$3\r\none\r\n");
	}

	@Test
	void zrevrangebyscoreRepliesWithTheScoresWhenAskedTo() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "WITHSCORES");

		assertThat(this.context.replies())
			.isEqualTo("*4\r\n$3\r\ntwo\r\n$6\r\n2000.0\r\n$3\r\none\r\n$6\r\n1000.0\r\n");
	}

	/**
	 * RESP3 has a type for a double and a client that speaks it expects each member to
	 * arrive paired with its score, rather than as one flat run of strings.
	 */
	@Test
	void zrevrangebyscorePairsEachMemberWithItsScoreInResp3() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one", "2000", "two");
		this.context.reset();
		this.context.protocolVersion(RespVersion.RESP3);

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "WITHSCORES");

		assertThat(this.context.replies())
			.isEqualTo("*2\r\n*2\r\n$3\r\ntwo\r\n,2000.0\r\n*2\r\n$3\r\none\r\n,1000.0\r\n");
	}

	@Test
	void zrevrangebyscoreRejectsAnOptionItDoesNotKnow() throws Exception {
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "REV");

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n");
	}

	@Test
	void zrevrangebyscoreNeedsBothEndsOfTheLimit() throws Exception {
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0", "LIMIT", "0");

		assertThat(this.context.replies()).isEqualTo("-ERR syntax error\r\n");
	}

	@Test
	void zrevrangebyscoreRejectsAnEndThatIsNotANumber() throws Exception {
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "now", "0");

		assertThat(this.context.replies()).isEqualTo("-ERR min or max is not a float\r\n");
	}

	@Test
	void zrevrangebyscoreTakesAtLeastAKeyAndBothEnds() throws Exception {
		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000");

		assertThat(this.context.replies())
			.isEqualTo("-ERR wrong number of arguments for 'zrevrangebyscore' command\r\n");
	}

	@Test
	void aSortedSetCommandAgainstAKeyHoldingSomethingElseIsAWrongTypeError() throws Exception {
		dispatch("HSET", EXPIRATIONS_KEY, "field", "value");
		this.context.reset();

		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one");

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	@Test
	void zrevrangebyscoreAgainstAKeyHoldingSomethingElseIsAWrongTypeError() throws Exception {
		dispatch("HSET", EXPIRATIONS_KEY, "field", "value");
		this.context.reset();

		dispatch("ZREVRANGEBYSCORE", EXPIRATIONS_KEY, "3000", "0");

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	@Test
	void typeNamesASortedSet() throws Exception {
		dispatch("ZADD", EXPIRATIONS_KEY, "1000", "one");
		this.context.reset();

		dispatch("TYPE", EXPIRATIONS_KEY);

		assertThat(this.context.replies()).isEqualTo("+zset\r\n");
	}

	private void dispatch(String... arguments) throws IOException {
		this.dispatcher.dispatch(this.context, argv(arguments));
	}

}
