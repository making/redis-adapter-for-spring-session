package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ZSetValue;

/**
 * The sorted-set commands: {@code ZADD}, {@code ZREM} and {@code ZREVRANGEBYSCORE}.
 *
 * <p>
 * They serve the alternative expiration store an indexed application may opt into, which
 * keeps every live session in one sorted set — {@code <namespace>:sessions:expirations} —
 * scored by the epoch millisecond it is due to expire at, rather than in one set per
 * minute. Saving a session scores it, deleting or expiring one removes it, and the
 * cleanup job asks for the sessions that are due with
 * {@code ZREVRANGEBYSCORE key <now> 0 LIMIT 0 <count>} and touches each of them.
 *
 * <p>
 * That is the whole of it: this store touches the <em>session</em> key rather than the
 * shadow key, so it only forces a stale session to be reclaimed. A session-expired event
 * still comes from the shadow key dying of its own TTL, which needs nothing beyond the
 * keyspace notifications the adapter already emits.
 *
 * <p>
 * Members are opaque bytes compared by value, never decoded: a session id in the sorted
 * set arrives serialized by whatever serializer the application configured.
 */
public final class ZSetCommands {

	/** What Redis answers when an end of a score range is not a number. */
	private static final String NOT_A_FLOAT = "ERR min or max is not a float";

	/** What Redis answers when the arguments parse but do not make a command. */
	private static final String SYNTAX_ERROR = "ERR syntax error";

	/** A count below zero asks for every member, as it does in Redis. */
	private static final long UNLIMITED = -1;

	/**
	 * Sorted-set order, reversed: highest score first, and among members sharing a score
	 * the highest bytes first. {@code ZREVRANGEBYSCORE} answers in exactly this order.
	 */
	private static final Comparator<Map.Entry<ByteArrayKey, Double>> HIGHEST_FIRST = Comparator
		.comparingDouble((Map.Entry<ByteArrayKey, Double> scored) -> scored.getValue())
		.thenComparing(Map.Entry::getKey)
		.reversed();

	private ZSetCommands() {
	}

	/**
	 * Registers every sorted-set command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("ZADD", ZSetCommands::zadd)
			.register("ZREM", ZSetCommands::zrem)
			.register("ZREVRANGEBYSCORE", ZSetCommands::zrevrangebyscore);
	}

	/**
	 * {@code ZADD key score member [score member ...]}: adds the members, replying with
	 * how many of them were not there already. A member that was there moves to its new
	 * score and is not counted, which is what re-saving a session does.
	 *
	 * <p>
	 * The options Redis takes here ({@code NX}, {@code XX}, {@code GT}, {@code LT},
	 * {@code CH}, {@code INCR}) are not accepted: nothing on the session path sends one,
	 * and each of them changes what the command does, so reading one as a score and
	 * failing is better than quietly ignoring it.
	 */
	private static void zadd(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 4) {
			throw RedisCommandException.wrongNumberOfArguments("zadd");
		}
		if (argv.size() % 2 != 0) {
			throw new RedisCommandException(SYNTAX_ERROR);
		}
		// Keyed by the raw argument arrays, which compare by identity, so a member named
		// twice in one command survives as two entries; the store applies them in order
		// and the later one wins, exactly as Redis does.
		Map<byte[], Double> scoredMembers = new LinkedHashMap<>();
		for (int i = 2; i < argv.size(); i += 2) {
			scoredMembers.put(argv.get(i + 1), score(argv.get(i)));
		}
		context.writer().writeInteger(context.store().zadd(argv.get(1), scoredMembers));
	}

	/**
	 * {@code ZREM key member [member ...]}: removes the members, replying with how many
	 * were actually removed. A sorted set that loses its last member is gone, as it is in
	 * Redis, and that is not a delete — no {@code del} keyspace notification is emitted
	 * for it.
	 */
	private static void zrem(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 3) {
			throw RedisCommandException.wrongNumberOfArguments("zrem");
		}
		context.writer().writeInteger(context.store().zrem(argv.get(1), argv.subList(2, argv.size())));
	}

	/**
	 * {@code ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]}: replies
	 * with the members scored within the range, highest score first. The maximum is
	 * written first because that is the end the reply starts from.
	 */
	private static void zrevrangebyscore(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 4) {
			throw RedisCommandException.wrongNumberOfArguments("zrevrangebyscore");
		}
		Bound max = bound(argv.get(2));
		Bound min = bound(argv.get(3));
		boolean withScores = false;
		long offset = 0;
		long count = UNLIMITED;
		for (int i = 4; i < argv.size(); i++) {
			switch (CommandArguments.upperCase(argv.get(i))) {
				case "WITHSCORES" -> withScores = true;
				case "LIMIT" -> {
					if (i + 2 >= argv.size()) {
						throw new RedisCommandException(SYNTAX_ERROR);
					}
					offset = CommandArguments.integer(argv.get(++i));
					count = CommandArguments.integer(argv.get(++i));
				}
				default -> throw new RedisCommandException(SYNTAX_ERROR);
			}
		}
		writeMembers(context, select(scoresOf(context, argv.get(1)), min, max, offset, count), withScores);
	}

	/**
	 * Returns the members of the range, highest score first.
	 * @param scores the whole sorted set
	 * @param min the low end of the score range
	 * @param max the high end of the score range
	 * @param offset how many of the matching members to skip; a negative offset skips
	 * past the end and so matches nothing, as it does in Redis
	 * @param count how many to take after that, or {@link #UNLIMITED} (any negative) for
	 * all of them
	 * @return the selected members with their scores, in reply order
	 */
	private static List<Map.Entry<ByteArrayKey, Double>> select(Map<ByteArrayKey, Double> scores, Bound min, Bound max,
			long offset, long count) {
		if (offset < 0 || count == 0) {
			return List.of();
		}
		List<Map.Entry<ByteArrayKey, Double>> matching = new ArrayList<>();
		for (Map.Entry<ByteArrayKey, Double> scored : scores.entrySet()) {
			if (min.isBelow(scored.getValue()) && max.isAbove(scored.getValue())) {
				matching.add(scored);
			}
		}
		matching.sort(HIGHEST_FIRST);
		if (offset >= matching.size()) {
			return List.of();
		}
		int from = (int) offset;
		int to = (count < 0) ? matching.size() : from + (int) Math.min(count, matching.size() - from);
		return matching.subList(from, to);
	}

	/**
	 * Writes the members, either on their own or each followed by its score. RESP2 has no
	 * type for a double, so a scored reply is one flat run of strings; RESP3 has one, and
	 * pairs each member with it.
	 */
	private static void writeMembers(CommandContext context, List<Map.Entry<ByteArrayKey, Double>> members,
			boolean withScores) throws IOException {
		RespWriter writer = context.writer();
		boolean paired = withScores && context.protocolVersion() == RespVersion.RESP3;
		writer.writeArrayHeader((withScores && !paired) ? 2 * members.size() : members.size());
		for (Map.Entry<ByteArrayKey, Double> member : members) {
			if (paired) {
				writer.writeArrayHeader(2);
			}
			writer.writeBulk(member.getKey().asBytes());
			if (withScores) {
				writer.writeDouble(member.getValue());
			}
		}
	}

	/**
	 * Reads the score of a member.
	 * @param argument the argument bytes
	 * @return the score
	 * @throws RedisCommandException if the argument is not a score
	 */
	private static double score(byte[] argument) {
		return CommandArguments.decimal(CommandArguments.text(argument))
			.orElseThrow(RedisCommandException::notAValidFloat);
	}

	/**
	 * Reads one end of a score range, which Redis marks as exclusive with a leading
	 * {@code (} and leaves open as an infinity.
	 * @param argument the argument bytes
	 * @return the range end
	 * @throws RedisCommandException if the argument is not a range end
	 */
	private static Bound bound(byte[] argument) {
		String text = CommandArguments.text(argument);
		boolean inclusive = !text.startsWith("(");
		double value = CommandArguments.decimal(inclusive ? text : text.substring(1))
			.orElseThrow(() -> new RedisCommandException(NOT_A_FLOAT));
		return new Bound(value, inclusive);
	}

	/**
	 * Returns the sorted set stored under {@code key}, or an empty one if the key is
	 * absent.
	 * @throws TypeMismatchException if the key holds something other than a sorted set
	 */
	private static Map<ByteArrayKey, Double> scoresOf(CommandContext context, byte[] key) {
		return switch (context.store().get(key)) {
			case null -> Map.of();
			case ZSetValue zset -> zset.scores();
			case RedisValue other ->
				throw new TypeMismatchException("ZREVRANGEBYSCORE against a key that does not hold a sorted set: "
						+ other.getClass().getSimpleName());
		};
	}

	/**
	 * One end of a score range: the score itself, and whether a member scored exactly
	 * there is inside the range.
	 *
	 * @param value the score at the end of the range
	 * @param inclusive whether that score is itself in the range
	 */
	private record Bound(double value, boolean inclusive) {

		/**
		 * Reports whether this low end of a range leaves {@code score} inside it.
		 * @param score the score to test
		 * @return {@code true} if {@code score} is not below this end
		 */
		boolean isBelow(double score) {
			return this.inclusive ? score >= this.value : score > this.value;
		}

		/**
		 * Reports whether this high end of a range leaves {@code score} inside it.
		 * @param score the score to test
		 * @return {@code true} if {@code score} is not above this end
		 */
		boolean isAbove(double score) {
			return this.inclusive ? score <= this.value : score < this.value;
		}
	}

}
