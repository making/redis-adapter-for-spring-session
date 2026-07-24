package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

import am.ik.redis.adapter.protocol.RespVersion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static am.ik.redis.adapter.command.TestCommandContext.argv;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionCommandsTest {

	/** The {@code HELLO} reply as RESP2 sees it: a map flattened into an array. */
	private static final String RESP2_IDENTITY = """
			*14\r
			$6\r
			server\r
			$5\r
			redis\r
			$7\r
			version\r
			$5\r
			7.4.0\r
			$5\r
			proto\r
			:2\r
			$2\r
			id\r
			:42\r
			$4\r
			mode\r
			$10\r
			standalone\r
			$4\r
			role\r
			$6\r
			master\r
			$7\r
			modules\r
			*0\r
			""";

	/** The same reply once {@code HELLO 3} switched the connection to RESP3. */
	private static final String RESP3_IDENTITY = """
			%7\r
			$6\r
			server\r
			$5\r
			redis\r
			$7\r
			version\r
			$5\r
			7.4.0\r
			$5\r
			proto\r
			:3\r
			$2\r
			id\r
			:42\r
			$4\r
			mode\r
			$10\r
			standalone\r
			$4\r
			role\r
			$6\r
			master\r
			$7\r
			modules\r
			*0\r
			""";

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final TestCommandContext context = new TestCommandContext();

	@Test
	void pingIsAnsweredWithPong() throws Exception {
		dispatch("PING");

		assertThat(this.context.replies()).isEqualTo("+PONG\r\n");
	}

	@Test
	void pingEchoesItsMessage() throws Exception {
		dispatch("PING", "hello");

		assertThat(this.context.replies()).isEqualTo("$5\r\nhello\r\n");
	}

	@Test
	void pingRejectsMoreThanOneMessage() throws Exception {
		dispatch("PING", "hello", "world");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'ping' command\r\n");
	}

	@Test
	void helloWithoutArgumentsReportsTheIdentityInTheCurrentProtocol() throws Exception {
		dispatch("HELLO");

		assertThat(this.context.replies()).isEqualTo(RESP2_IDENTITY);
		assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
	}

	@Test
	void helloTwoKeepsTheConnectionOnResp2() throws Exception {
		dispatch("HELLO", "2");

		assertThat(this.context.replies()).isEqualTo(RESP2_IDENTITY);
		assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
	}

	@Test
	void helloThreeUpgradesTheConnectionAndRepliesInResp3() throws Exception {
		dispatch("HELLO", "3");

		assertThat(this.context.replies()).isEqualTo(RESP3_IDENTITY);
		assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP3);
	}

	@Test
	void helloAcceptsCredentialsAndAClientName() throws Exception {
		dispatch("HELLO", "3", "AUTH", "user", "password", "SETNAME", "app");

		assertThat(this.context.replies()).isEqualTo(RESP3_IDENTITY);
		assertThat(this.context.clientName()).isEqualTo("app");
	}

	@Test
	void helloRejectsAnUnsupportedProtocolVersion() throws Exception {
		dispatch("HELLO", "4");

		assertThat(this.context.replies()).isEqualTo("-NOPROTO unsupported protocol version\r\n");
		assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
	}

	@Test
	void helloRejectsAProtocolVersionThatIsNotANumber() throws Exception {
		dispatch("HELLO", "three");

		assertThat(this.context.replies()).isEqualTo("-ERR Protocol version is not an integer or out of range\r\n");
	}

	@Test
	void helloRejectsAnUnknownOption() throws Exception {
		dispatch("HELLO", "3", "SETNAME");

		assertThat(this.context.replies()).isEqualTo("-ERR Syntax error in HELLO option 'SETNAME'\r\n");
		assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
	}

	@Test
	void authIsAcceptedBecauseNoPasswordIsConfigured() throws Exception {
		dispatch("AUTH", "password");
		dispatch("AUTH", "user", "password");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n+OK\r\n");
	}

	@Test
	void authRejectsMissingCredentials() throws Exception {
		dispatch("AUTH");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'auth' command\r\n");
	}

	@Test
	void clientIdReportsTheConnectionIdentifier() throws Exception {
		dispatch("CLIENT", "ID");

		assertThat(this.context.replies()).isEqualTo(":42\r\n");
	}

	@Test
	void clientSetnameIsReadBackByGetname() throws Exception {
		dispatch("CLIENT", "SETNAME", "app");
		dispatch("CLIENT", "GETNAME");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n$3\r\napp\r\n");
	}

	@Test
	void clientGetnameIsNullUntilANameIsSet() throws Exception {
		dispatch("CLIENT", "GETNAME");

		assertThat(this.context.replies()).isEqualTo("$-1\r\n");
	}

	@Test
	void clientSetinfoIsAccepted() throws Exception {
		dispatch("CLIENT", "SETINFO", "lib-name", "some-client");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
	}

	@Test
	void clientSubcommandsWithoutMeaningForTheAdapterAreAccepted() throws Exception {
		dispatch("CLIENT", "NO-EVICT", "on");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
	}

	@Test
	void clientSetnameRejectsAMissingName() throws Exception {
		dispatch("CLIENT", "SETNAME");

		assertThat(this.context.replies()).isEqualTo("-ERR wrong number of arguments for 'client|setname' command\r\n");
	}

	@Test
	void selectSwitchesTheDatabase() throws Exception {
		dispatch("SELECT", "1");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
		assertThat(this.context.databaseIndex()).isEqualTo(1);
	}

	@Test
	void selectRejectsADatabaseTheServerDoesNotHave() throws Exception {
		dispatch("SELECT", "2");

		assertThat(this.context.replies()).isEqualTo("-ERR DB index is out of range\r\n");
		assertThat(this.context.databaseIndex()).isZero();
	}

	@Test
	void selectRejectsANegativeDatabase() throws Exception {
		dispatch("SELECT", "-1");

		assertThat(this.context.replies()).isEqualTo("-ERR DB index is out of range\r\n");
	}

	@Test
	void selectRejectsADatabaseThatIsNotANumber() throws Exception {
		dispatch("SELECT", "one");

		assertThat(this.context.replies()).isEqualTo("-ERR value is not an integer or out of range\r\n");
	}

	@Test
	void quitAcknowledgesAndAsksForTheConnectionToBeClosed() throws Exception {
		dispatch("QUIT");

		assertThat(this.context.replies()).isEqualTo("+OK\r\n");
		assertThat(this.context.isCloseRequested()).isTrue();
	}

	@Test
	void commandsAreMatchedRegardlessOfCase() throws Exception {
		dispatch("hello", "3");

		assertThat(this.context.replies()).isEqualTo(RESP3_IDENTITY);
	}

	private void dispatch(String... arguments) throws IOException {
		List<byte[]> argv = argv(arguments);
		this.dispatcher.dispatch(this.context, argv);
	}

	/**
	 * On a server with a password, a connection starts out unable to do anything but
	 * authenticate.
	 */
	@Nested
	class WhenAPasswordIsRequired {

		private final TestCommandContext context = new TestCommandContext()
			.authenticator(Authenticator.password("s3cret"));

		@Test
		void authWithTheRightPasswordOpensTheConnection() throws Exception {
			dispatch("AUTH", "s3cret");
			dispatch("PING");

			assertThat(this.context.replies()).isEqualTo("+OK\r\n+PONG\r\n");
			assertThat(this.context.authenticated()).isTrue();
		}

		@Test
		void authWithTheDefaultUserNameSpeltOutWorksToo() throws Exception {
			dispatch("AUTH", "default", "s3cret");

			assertThat(this.context.replies()).isEqualTo("+OK\r\n");
			assertThat(this.context.authenticated()).isTrue();
		}

		@Test
		void authWithTheWrongPasswordIsRejected() throws Exception {
			dispatch("AUTH", "guess");

			assertThat(this.context.replies())
				.isEqualTo("-WRONGPASS invalid username-password pair or user is disabled.\r\n");
			assertThat(this.context.authenticated()).isFalse();
		}

		@Test
		void authWithAnotherUserNameIsRejected() throws Exception {
			dispatch("AUTH", "someone-else", "s3cret");

			assertThat(this.context.replies())
				.isEqualTo("-WRONGPASS invalid username-password pair or user is disabled.\r\n");
			assertThat(this.context.authenticated()).isFalse();
		}

		@Test
		void aFailedAuthDoesNotUndoAnEarlierSuccessfulOne() throws Exception {
			dispatch("AUTH", "s3cret");
			dispatch("AUTH", "guess");
			dispatch("PING");

			assertThat(this.context.replies())
				.isEqualTo("+OK\r\n-WRONGPASS invalid username-password pair or user is disabled.\r\n+PONG\r\n");
		}

		@Test
		void commandsBeforeAuthenticationAreRefused() throws Exception {
			dispatch("PING");
			dispatch("SELECT", "1");
			dispatch("CLIENT", "ID");

			assertThat(this.context.replies()).isEqualTo("-NOAUTH Authentication required.\r\n"
					+ "-NOAUTH Authentication required.\r\n-NOAUTH Authentication required.\r\n");
		}

		@Test
		void helloAuthenticatesAndNegotiatesInOneRoundTrip() throws Exception {
			dispatch("HELLO", "3", "AUTH", "default", "s3cret");

			assertThat(this.context.replies()).isEqualTo(RESP3_IDENTITY);
			assertThat(this.context.authenticated()).isTrue();
			assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP3);
		}

		@Test
		void helloWithTheWrongPasswordChangesNothing() throws Exception {
			dispatch("HELLO", "3", "AUTH", "default", "guess");

			assertThat(this.context.replies())
				.isEqualTo("-WRONGPASS invalid username-password pair or user is disabled.\r\n");
			assertThat(this.context.authenticated()).isFalse();
			assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
		}

		@Test
		void helloWithoutCredentialsSaysHowToAuthenticate() throws Exception {
			dispatch("HELLO", "3");

			assertThat(this.context.replies())
				.isEqualTo("-NOAUTH HELLO must be called with the client already authenticated, otherwise the "
						+ "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and "
						+ "select the RESP protocol version.\r\n");
			assertThat(this.context.protocolVersion()).isEqualTo(RespVersion.RESP2);
		}

		@Test
		void helloWithoutArgumentsIsRefusedBeforeAuthentication() throws Exception {
			dispatch("HELLO");

			assertThat(this.context.replies()).startsWith("-NOAUTH HELLO must be called");
		}

		@Test
		void helloReportsTheIdentityOnceTheConnectionIsAuthenticated() throws Exception {
			dispatch("AUTH", "s3cret");
			dispatch("HELLO", "3");

			assertThat(this.context.replies()).isEqualTo("+OK\r\n" + RESP3_IDENTITY);
		}

		@Test
		void quitIsAllowedBeforeAuthenticating() throws Exception {
			dispatch("QUIT");

			assertThat(this.context.replies()).isEqualTo("+OK\r\n");
			assertThat(this.context.isCloseRequested()).isTrue();
		}

		private void dispatch(String... arguments) throws IOException {
			ConnectionCommandsTest.this.dispatcher.dispatch(this.context, argv(arguments));
		}

	}

}
