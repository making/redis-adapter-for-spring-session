package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.TypeMismatchException;

/**
 * The hash commands: {@code HGETALL}, {@code HSET}, {@code HMSET} and {@code HGET}.
 *
 * <p>
 * A session is one hash, so these carry all of its data. Field names are UTF-8 text on
 * the wire ({@code creationTime}, {@code sessionAttr:<name>}, …) and field values are
 * opaque blobs, including <strong>zero-length</strong> ones: removing a session attribute
 * stores an empty value rather than dropping the field, so an empty value is data and
 * never a deletion.
 *
 * <p>
 * {@code HSET} and {@code HMSET} differ only in their reply — clients emit either — and
 * both write the whole batch in one store operation, so a save is atomic per key.
 */
public final class HashCommands {

	private HashCommands() {
	}

	/**
	 * Registers every hash command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("HGETALL", HashCommands::hgetall)
			.register("HSET", HashCommands::hset)
			.register("HMSET", HashCommands::hmset)
			.register("HGET", HashCommands::hget);
	}

	/**
	 * {@code HGETALL key}: replies with every field and value of the hash. A missing key
	 * is an <strong>empty</strong> map rather than a null, which is how Spring Session
	 * reads "this session is gone".
	 */
	private static void hgetall(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("hgetall");
		}
		Map<ByteArrayKey, byte[]> fields = fieldsOf(context, argv.get(1), "HGETALL");
		RespWriter writer = context.writer();
		writer.writeMapHeader(fields.size());
		for (Map.Entry<ByteArrayKey, byte[]> field : fields.entrySet()) {
			writer.writeBulk(field.getKey().asBytes());
			writer.writeBulk(field.getValue());
		}
	}

	/**
	 * {@code HGET key field}: replies with the field value, or a null if either the key
	 * or the field is absent.
	 */
	private static void hget(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 3) {
			throw RedisCommandException.wrongNumberOfArguments("hget");
		}
		context.writer().writeBulk(fieldsOf(context, argv.get(1), "HGET").get(ByteArrayKey.of(argv.get(2))));
	}

	/**
	 * {@code HSET key field value [field value ...]}: sets the fields and replies with
	 * how many of them did not exist before.
	 */
	private static void hset(CommandContext context, List<byte[]> argv) throws IOException {
		context.writer().writeInteger(context.store().hset(argv.get(1), pairs(argv, "hset")));
	}

	/**
	 * {@code HMSET key field value [field value ...]}: the same write as {@code HSET},
	 * acknowledged instead of counted.
	 */
	private static void hmset(CommandContext context, List<byte[]> argv) throws IOException {
		context.store().hset(argv.get(1), pairs(argv, "hmset"));
		context.writer().writeSimpleString("OK");
	}

	/**
	 * Reads the field/value pairs that follow the key.
	 *
	 * <p>
	 * The returned map keys are the raw argument arrays, which compare by identity, so a
	 * field named twice in one command survives as two entries. The store applies them in
	 * order and the later one wins, exactly as Redis does.
	 * @param argv the argument vector
	 * @param command the command name, for the error message
	 * @return the field-to-value pairs in wire order
	 * @throws RedisCommandException if a field has no value
	 */
	private static Map<byte[], byte[]> pairs(List<byte[]> argv, String command) {
		if (argv.size() < 4 || argv.size() % 2 != 0) {
			throw RedisCommandException.wrongNumberOfArguments(command);
		}
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		for (int i = 2; i < argv.size(); i += 2) {
			fields.put(argv.get(i), argv.get(i + 1));
		}
		return fields;
	}

	/**
	 * Returns the fields of the hash stored under {@code key}, or an empty map if the key
	 * is absent.
	 * @param context the connection
	 * @param key the key bytes
	 * @param command the command name, for the error message
	 * @return the field-to-value map, never {@code null}
	 * @throws TypeMismatchException if the key holds something other than a hash
	 */
	private static Map<ByteArrayKey, byte[]> fieldsOf(CommandContext context, byte[] key, String command) {
		return switch (context.store().get(key)) {
			case null -> Map.of();
			case HashValue hash -> hash.fields();
			case RedisValue other -> throw new TypeMismatchException(
					command + " against a key that does not hold a hash: " + other.getClass().getSimpleName());
		};
	}

}
