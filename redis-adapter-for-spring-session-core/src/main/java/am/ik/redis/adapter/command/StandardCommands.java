package am.ik.redis.adapter.command;

/**
 * The command set the adapter speaks.
 *
 * <p>
 * This is the single place that lists every command family, so what the adapter answers
 * is answered by one file. Callers that want a different set — an extra command, or a
 * deliberately smaller surface — build their own {@link CommandDispatcher} instead.
 */
public final class StandardCommands {

	private StandardCommands() {
	}

	/**
	 * Builds a dispatcher holding every command the adapter implements.
	 * @return a new dispatcher
	 */
	public static CommandDispatcher dispatcher() {
		CommandDispatcher.Builder builder = CommandDispatcher.builder();
		registerTo(builder);
		return builder.build();
	}

	/**
	 * Registers every command the adapter implements on a dispatcher, so that a caller
	 * can add commands of its own alongside them.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		ConnectionCommands.registerTo(builder);
		ServerCommands.registerTo(builder);
	}

}
