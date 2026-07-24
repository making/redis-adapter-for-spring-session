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

	@Test
	void configGetReportsNoParameters() throws Exception {
		dispatch("CONFIG", "GET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("*0\r\n");
	}

	@Test
	void configGetReportsNoParametersInResp3() throws Exception {
		this.context.protocolVersion(RespVersion.RESP3);

		dispatch("CONFIG", "GET", "notify-keyspace-events");

		assertThat(this.context.replies()).isEqualTo("%0\r\n");
	}

	@Test
	void configSetIsAcknowledged() throws Exception {
		dispatch("CONFIG", "SET", "notify-keyspace-events", "gxE");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
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
