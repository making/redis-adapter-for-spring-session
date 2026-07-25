package am.ik.redis.adapter.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ValueTooLargeException;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CommandDispatcherTest {

	private final TestCommandContext context = new TestCommandContext();

	@Test
	void routesByNameIgnoringCase() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor(
				(connection, argv) -> connection.writer().writeSimpleString("PONG"));

		dispatcher.dispatch(this.context, argv("PING"));
		dispatcher.dispatch(this.context, argv("ping"));
		dispatcher.dispatch(this.context, argv("PiNg"));

		assertThat(this.context.replies()).isEqualTo("+PONG\r\n+PONG\r\n+PONG\r\n");
	}

	@Test
	void passesTheWholeArgumentVectorToTheHandler() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor(
				(connection, argv) -> connection.writer().writeInteger(argv.size()));

		dispatcher.dispatch(this.context, argv("PING", "one", "two"));

		assertThat(this.context.replies()).isEqualTo(":3\r\n");
	}

	@Test
	void answersAnUnknownCommandWithAnError() throws Exception {
		CommandDispatcher dispatcher = CommandDispatcher.builder().build();

		dispatcher.dispatch(this.context, argv("frobnicate", "x"));

		assertThat(this.context.replies()).isEqualTo("-ERR unknown command 'frobnicate'\r\n");
	}

	@Test
	void stripsLineBreaksFromAnUnknownCommandName() throws Exception {
		CommandDispatcher dispatcher = CommandDispatcher.builder().build();

		dispatcher.dispatch(this.context, argv("FO\r\nO"));

		assertThat(this.context.replies()).isEqualTo("-ERR unknown command 'FO..O'\r\n");
	}

	@Test
	void truncatesAnOverlongUnknownCommandName() throws Exception {
		CommandDispatcher dispatcher = CommandDispatcher.builder().build();

		dispatcher.dispatch(this.context, argv("A".repeat(200)));

		assertThat(this.context.replies()).isEqualTo("-ERR unknown command '" + "A".repeat(128) + "...'\r\n");
	}

	@Test
	void turnsACommandExceptionIntoItsErrorText() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor((connection, argv) -> {
			throw RedisCommandException.wrongNumberOfArguments("ping");
		});

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'ping' command\r\n");
	}

	@Test
	void turnsABackendTypeMismatchIntoWrongType() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor((connection, argv) -> {
			throw new TypeMismatchException("key holds a hash");
		});

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies())
			.isEqualTo("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
	}

	/**
	 * A value the backend will not take is the caller's to fix, so it must not arrive as
	 * {@code internal error} — that sends whoever is holding the exception looking for a
	 * bug in the adapter.
	 */
	@Test
	void turnsAValueTheBackendWillNotStoreIntoAnErrorThatSaysSo() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor((connection, argv) -> {
			throw new ValueTooLargeException("etcd refused /v3/kv/txn: etcdserver: request is too large");
		});

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies()).isEqualTo("-ERR value too large for the backend\r\n");
	}

	@Test
	void turnsAnUnexpectedFailureIntoAnInternalError() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor((connection, argv) -> {
			throw new IllegalStateException("boom");
		});

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies()).isEqualTo("-ERR internal error\r\n");
	}

	@Test
	void refusesACommandThatNeedsAuthenticationUntilTheConnectionAuthenticates() throws Exception {
		TestCommandContext protectedContext = new TestCommandContext().authenticator(Authenticator.password("s3cret"));
		CommandDispatcher dispatcher = dispatcherFor(
				(connection, argv) -> connection.writer().writeSimpleString("RAN"));

		dispatcher.dispatch(protectedContext, argv("PING"));
		protectedContext.authenticate(Authenticator.DEFAULT_USERNAME, "s3cret");
		dispatcher.dispatch(protectedContext, argv("PING"));

		assertThat(protectedContext.replies()).isEqualTo("-NOAUTH Authentication required.\r\n+RAN\r\n");
	}

	@Test
	void runsACommandRegisteredAsUnauthenticatedBeforeAnyCredentials() throws Exception {
		TestCommandContext protectedContext = new TestCommandContext().authenticator(Authenticator.password("s3cret"));
		CommandDispatcher dispatcher = CommandDispatcher.builder()
			.register("HELLO", (connection, argv) -> connection.writer().writeSimpleString("RAN"),
					CommandAvailability.UNAUTHENTICATED)
			.build();

		dispatcher.dispatch(protectedContext, argv("HELLO"));

		assertThat(protectedContext.replies()).isEqualTo("+RAN\r\n");
	}

	@Test
	void refusesACommandThatIsNotAvailableWhileTheConnectionIsSubscribed() throws Exception {
		CommandDispatcher dispatcher = dispatcherFor(
				(connection, argv) -> connection.writer().writeSimpleString("RAN"));
		subscribe(this.context);

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies()).isEqualTo("-ERR Can't execute 'ping': only (P|S)SUBSCRIBE / "
				+ "(P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context\r\n");
	}

	@Test
	void runsACommandRegisteredAsAvailableWhileSubscribed() throws Exception {
		CommandDispatcher dispatcher = CommandDispatcher.builder()
			.register("PING", (connection, argv) -> connection.writer().writeSimpleString("RAN"),
					CommandAvailability.WHILE_SUBSCRIBED)
			.build();
		subscribe(this.context);

		dispatcher.dispatch(this.context, argv("PING"));

		assertThat(this.context.replies()).isEqualTo("+RAN\r\n");
	}

	/**
	 * A connection that has not authenticated cannot have subscribed, so it must hear
	 * about the credentials it is missing rather than about subscriber mode.
	 */
	@Test
	void checksAuthenticationBeforeSubscriberMode() throws Exception {
		TestCommandContext protectedContext = new TestCommandContext().authenticator(Authenticator.password("s3cret"));
		CommandDispatcher dispatcher = dispatcherFor(
				(connection, argv) -> connection.writer().writeSimpleString("RAN"));
		subscribe(protectedContext);

		dispatcher.dispatch(protectedContext, argv("PING"));

		assertThat(protectedContext.replies()).isEqualTo("-NOAUTH Authentication required.\r\n");
	}

	private static void subscribe(TestCommandContext context) {
		context.pubSub().subscribe(context.subscriber(), "news".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void reportsAnUnknownCommandAsUnknownEvenBeforeAuthentication() throws Exception {
		TestCommandContext protectedContext = new TestCommandContext().authenticator(Authenticator.password("s3cret"));
		CommandDispatcher dispatcher = CommandDispatcher.builder().build();

		dispatcher.dispatch(protectedContext, argv("frobnicate"));

		assertThat(protectedContext.replies()).isEqualTo("-ERR unknown command 'frobnicate'\r\n");
	}

	@Test
	void reportsWhichCommandsAreRegistered() {
		CommandDispatcher dispatcher = dispatcherFor((connection, argv) -> {
		});

		assertThat(dispatcher.supports("PING")).isTrue();
		assertThat(dispatcher.supports("ping")).isTrue();
		assertThat(dispatcher.supports("HGETALL")).isFalse();
	}

	@Test
	void rejectsRegisteringACommandTwice() {
		CommandDispatcher.Builder builder = dispatcherBuilderFor((connection, argv) -> {
		});

		assertThatIllegalArgumentException().isThrownBy(() -> builder.register("ping", (connection, argv) -> {
		})).withMessage("command is already registered: PING");
	}

	@Test
	void rejectsAnEmptyArgumentVector() {
		CommandDispatcher dispatcher = CommandDispatcher.builder().build();

		assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.dispatch(this.context, List.of()))
			.withMessage("argv must contain at least the command name");
	}

	private static CommandDispatcher dispatcherFor(CommandHandler handler) {
		return dispatcherBuilderFor(handler).build();
	}

	private static CommandDispatcher.Builder dispatcherBuilderFor(CommandHandler handler) {
		return CommandDispatcher.builder().register("PING", handler);
	}

}
