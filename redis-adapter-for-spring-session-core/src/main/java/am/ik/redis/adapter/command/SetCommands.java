package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.TypeMismatchException;

/**
 * The set commands: {@code SADD}, {@code SREM} and {@code SMEMBERS}.
 *
 * <p>
 * Indexed mode keeps two kinds of set. The principal index
 * ({@code spring:session:index:...:<principal>}) holds the ids of the sessions belonging
 * to one user, and an expirations bucket ({@code spring:session:expirations:<minute>})
 * holds the sessions due to expire in one minute of wall-clock time. Both are read back
 * whole with {@code SMEMBERS} and shrunk one member at a time with {@code SREM}.
 *
 * <p>
 * Members are opaque bytes compared by value, never decoded: an id in a set arrives
 * serialized by whatever serializer the application configured.
 */
public final class SetCommands {

	private SetCommands() {
	}

	/**
	 * Registers every set command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("SADD", SetCommands::sadd)
			.register("SREM", SetCommands::srem)
			.register("SMEMBERS", SetCommands::smembers);
	}

	/**
	 * {@code SADD key member [member ...]}: adds the members, replying with how many of
	 * them were not there already.
	 */
	private static void sadd(CommandContext context, List<byte[]> argv) throws IOException {
		context.writer().writeInteger(context.store().sadd(argv.get(1), members(argv, "sadd")));
	}

	/**
	 * {@code SREM key member [member ...]}: removes the members, replying with how many
	 * were actually removed. A set that loses its last member is gone, as it is in Redis,
	 * and that is not a delete — no {@code del} keyspace notification is emitted for it.
	 */
	private static void srem(CommandContext context, List<byte[]> argv) throws IOException {
		context.writer().writeInteger(context.store().srem(argv.get(1), members(argv, "srem")));
	}

	/**
	 * {@code SMEMBERS key}: replies with every member. A key that is not there is an
	 * <strong>empty</strong> array rather than a null, which is how Spring Session reads
	 * "this principal has no sessions".
	 */
	private static void smembers(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("smembers");
		}
		RespWriter writer = context.writer();
		Set<ByteArrayKey> members = membersOf(context, argv.get(1));
		writer.writeArrayHeader(members.size());
		for (ByteArrayKey member : members) {
			writer.writeBulk(member.asBytes());
		}
	}

	/**
	 * Reads the members that follow the key.
	 * @param argv the argument vector
	 * @param command the command name, for the error message
	 * @return the members in wire order
	 * @throws RedisCommandException if no member was given
	 */
	private static List<byte[]> members(List<byte[]> argv, String command) {
		if (argv.size() < 3) {
			throw RedisCommandException.wrongNumberOfArguments(command);
		}
		return argv.subList(2, argv.size());
	}

	/**
	 * Returns the members of the set stored under {@code key}, or an empty set if the key
	 * is absent.
	 * @throws TypeMismatchException if the key holds something other than a set
	 */
	private static Set<ByteArrayKey> membersOf(CommandContext context, byte[] key) {
		return switch (context.store().get(key)) {
			case null -> Set.of();
			case SetValue set -> set.members();
			case RedisValue other -> throw new TypeMismatchException(
					"SMEMBERS against a key that does not hold a set: " + other.getClass().getSimpleName());
		};
	}

}
