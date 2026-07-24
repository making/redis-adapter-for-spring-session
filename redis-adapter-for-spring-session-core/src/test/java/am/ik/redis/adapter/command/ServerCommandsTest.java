package am.ik.redis.adapter.command;

import java.io.IOException;

import am.ik.redis.adapter.protocol.RespVersion;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;

class ServerCommandsTest {

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final TestCommandContext context = new TestCommandContext();

	@Test
	void commandPublishesNoCommandTable() throws Exception {
		dispatch("COMMAND");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void commandCountIsZero() throws Exception {
		dispatch("COMMAND", "COUNT");

		assertThat(this.context.replies()).isEqualTo(":0\r\n");
	}

	@Test
	void commandDocsIsAnEmptyMap() throws Exception {
		dispatch("COMMAND", "DOCS");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void commandDocsIsAnEmptyMapInResp3() throws Exception {
		this.context.protocolVersion(RespVersion.RESP3);

		dispatch("COMMAND", "DOCS");

		assertThat(this.context.replies()).isEqualTo("%0\r\n");
	}

	@Test
	void commandInfoAnswersOneNullPerRequestedCommand() throws Exception {
		dispatch("COMMAND", "INFO", "get", "set");

		assertThat(this.context.replies()).isEqualTo("*2\r\n*-1\r\n*-1\r\n");
	}

	@Test
	void commandSubcommandsWithoutMeaningForTheAdapterAreAnsweredEmpty() throws Exception {
		dispatch("COMMAND", "GETKEYS", "get", "key");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	/**
	 * Spring Session reads this parameter before it subscribes and refuses to start if
	 * the reply is an error. The adapter always emits keyspace events, so it reports the
	 * flags that say so — which also spares the client the follow-up {@code CONFIG SET}.
	 */
	@Test
	void configGetAnswersTheKeyspaceNotificationFlags() throws Exception {
		dispatch("CONFIG", "GET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("""
				*2\r
				$22\r
				notify-keyspace-events\r
				$3\r
				Egx\r
				""");
	}

	@Test
	void configGetAnswersAMapInResp3() throws Exception {
		this.context.protocolVersion(RespVersion.RESP3);

		dispatch("CONFIG", "GET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("""
				%1\r
				$22\r
				notify-keyspace-events\r
				$3\r
				Egx\r
				""");
	}

	@Test
	void configGetMatchesParameterNamesAsAGlob() throws Exception {
		dispatch("CONFIG", "GET", "notify-*");

		assertThat(this.context.replies()).isEqualTo("""
				*2\r
				$22\r
				notify-keyspace-events\r
				$3\r
				Egx\r
				""");
	}

	@Test
	void configGetOfAParameterTheAdapterDoesNotKeepIsEmptyRatherThanAnError() throws Exception {
		dispatch("CONFIG", "GET", "maxmemory");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void configSetIsAcknowledged() throws Exception {
		dispatch("CONFIG", "SET", "notify-keyspace-events", "gxE");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
	}

	/**
	 * The value is remembered so a client reading it back sees what it wrote, but it
	 * never changes behaviour: the adapter emits the {@code del} and {@code expired}
	 * events Spring Session needs whatever the flags say.
	 */
	@Test
	void configSetIsRememberedForTheWholeServer() throws Exception {
		dispatch("CONFIG", "SET", "notify-keyspace-events", "KEA");
		this.context.reset();

		dispatch("CONFIG", "GET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("""
				*2\r
				$22\r
				notify-keyspace-events\r
				$3\r
				KEA\r
				""");
	}

	@Test
	void configSetNeedsAValueForEveryParameter() throws Exception {
		dispatch("CONFIG", "SET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'config|set' command\r\n");
	}

	@Test
	void configGetRejectsAMissingParameterName() throws Exception {
		dispatch("CONFIG", "GET");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'config|get' command\r\n");
	}

	@Test
	void configRejectsAMissingSubcommand() throws Exception {
		dispatch("CONFIG");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'config' command\r\n");
	}

	private void dispatch(String... arguments) throws IOException {
		this.dispatcher.dispatch(this.context, argv(arguments));
	}

}
