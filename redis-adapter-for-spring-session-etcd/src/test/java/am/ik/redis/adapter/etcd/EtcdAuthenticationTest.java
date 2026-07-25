package am.ik.redis.adapter.etcd;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backend against an etcd with authentication turned on.
 *
 * <p>
 * A production cluster is usually protected, so this is the ordinary case rather than an
 * exotic one — and it is a case a store can fail at long after it started: etcd's tokens
 * do not last forever, and a cluster that is restarted has never heard of the one it
 * handed out. A backend that only authenticated once would work for an afternoon and then
 * stop.
 *
 * <p>
 * This cluster is one of its own, because enabling authentication on the shared one would
 * lock every other test out of it. Authentication is turned on through etcd's own API
 * rather than with {@code etcdctl}, so the container needs nothing installed in it.
 */
class EtcdAuthenticationTest {

	private static final String USER = "root";

	private static final String PASSWORD = "s3cret-etcd-password";

	private static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private static final AtomicInteger keyspace = new AtomicInteger();

	private static final GenericContainer<?> etcd = new GenericContainer<>(EtcdCluster.IMAGE).withExposedPorts(2379)
		.withCommand("etcd", "--advertise-client-urls", "http://0.0.0.0:2379", "--listen-client-urls",
				"http://0.0.0.0:2379")
		.waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private static String endpoint;

	@BeforeAll
	static void enableAuthentication() throws Exception {
		etcd.start();
		endpoint = "http://" + etcd.getHost() + ":" + etcd.getMappedPort(2379);
		// etcd only enables authentication once there is a root user holding the root
		// role.
		call("/v3/auth/user/add", "{\"name\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}", null);
		call("/v3/auth/user/grant", "{\"user\":\"" + USER + "\",\"role\":\"root\"}", null);
		call("/v3/auth/enable", "{}", null);
	}

	@AfterAll
	static void stop() {
		etcd.stop();
	}

	@Test
	void aStoreWithCredentialsReadsWritesAndHearsAboutRemovals(TestInfo test) {
		try (EtcdKeyValueStore store = store(USER, PASSWORD, prefix(test))) {
			RecordingListener listener = new RecordingListener();
			store.addKeyEventListener(listener);

			store.hset(b("session"), Map.of(b("user"), b("alice")));
			assertThat(store.exists(b("session"))).isTrue();
			store.delete(b("session"));

			// The watch is authenticated too, and it is the one request that is opened
			// once
			// and then read for as long as it lasts.
			listener.awaitEvent("deleted session");
		}
	}

	@Test
	void aStoreWithNoCredentialsIsRefused(TestInfo test) {
		try (EtcdKeyValueStore store = store(null, null, prefix(test))) {
			assertThatThrownBy(() -> store.append(b("k"), b("v"))).isInstanceOf(EtcdException.class)
				.hasMessageContaining("etcd refused");
		}
	}

	/**
	 * The password is what an operator is most afraid of finding in a log, and a failure
	 * to authenticate is exactly when something gets logged. The reason etcd gives is
	 * reported; the credential never is.
	 */
	@Test
	void theWrongPasswordIsRefusedWithoutRepeatingIt(TestInfo test) {
		try (EtcdKeyValueStore store = store(USER, "not-the-password", prefix(test))) {
			assertThatThrownBy(() -> store.append(b("k"), b("v"))).isInstanceOf(EtcdException.class)
				.hasMessageContaining("refused the credentials of user " + USER)
				.hasMessageNotContaining("not-the-password");
		}
	}

	/**
	 * A token the cluster no longer knows — it expired, or the cluster was restarted —
	 * must cost one request rather than the store.
	 */
	@Test
	void aTokenTheClusterHasForgottenIsFetchedAgain(TestInfo test) throws Exception {
		try (EtcdKeyValueStore store = store(USER, PASSWORD, prefix(test))) {
			store.append(b("before"), b("v"));

			forgetEveryToken();

			assertThat(store.append(b("after"), b("v"))).isEqualTo(1);
		}
	}

	/**
	 * The same for the watch, which has to recover <em>on its own</em>.
	 *
	 * <p>
	 * A watch is opened once and then read for as long as it lasts, so it cannot retry
	 * the way a request can: the token it was opened with is only ever questioned when it
	 * has to be opened again, after a connection is lost. That is the moment this test
	 * builds — the cluster has forgotten the token <em>and</em> the connection is cut —
	 * because a watch left asking with a dead token would leave an application
	 * permanently without session events while nothing said so.
	 *
	 * <p>
	 * The watching store issues no command of its own after the token is forgotten; a
	 * command would fetch a fresh token for it and prove nothing. Another store, with a
	 * token of its own, does the removing: exactly the replica whose work this one has to
	 * hear about.
	 */
	@Test
	void aWatchReopenedWithAForgottenTokenAuthenticatesAgain(TestInfo test) throws Exception {
		String prefix = prefix(test);
		try (TcpProxy proxy = TcpProxy.to(endpoint);
				EtcdKeyValueStore watching = store(USER, PASSWORD, prefix, proxy.endpoint());
				EtcdKeyValueStore other = store(USER, PASSWORD, prefix, endpoint)) {
			RecordingListener listener = new RecordingListener();
			watching.addKeyEventListener(listener);
			other.append(b("before"), b("v"));
			other.delete(b("before"));
			listener.awaitEvent("deleted before");

			forgetEveryToken();
			// The watch now has to be opened again, with a token etcd has never heard of.
			proxy.cutConnections();

			other.append(b("after"), b("v"));
			other.delete(b("after"));

			listener.awaitEvent("deleted after");
		}
	}

	/**
	 * Turning authentication off and on again is how etcd is made to forget the tokens it
	 * has handed out, and it needs no restart and no waiting for one to age out.
	 * @throws Exception if etcd cannot be reached
	 */
	private static void forgetEveryToken() throws Exception {
		String token = authenticate();
		call("/v3/auth/disable", "{}", token);
		call("/v3/auth/enable", "{}", null);
	}

	private static String authenticate() throws Exception {
		String response = call("/v3/auth/authenticate", "{\"name\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}",
				null);
		String token = Json.text(Json.parseObject(response).get("token"));
		assertThat(token).as("the token etcd handed out").isNotNull();
		return token;
	}

	private static String call(String path, String body, @Nullable String token)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			request.header("Authorization", token);
		}
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("%s answered %s", path, response.body()).isEqualTo(200);
		return response.body();
	}

	private static EtcdKeyValueStore store(@Nullable String username, @Nullable String password, String prefix) {
		return store(username, password, prefix, endpoint);
	}

	private static EtcdKeyValueStore store(@Nullable String username, @Nullable String password, String prefix,
			String endpoint) {
		return EtcdKeyValueStore.builder()
			.endpoints(List.of(endpoint))
			.keyPrefix(prefix)
			.credentials(username, password)
			.watchRetryDelay(Duration.ofMillis(100))
			.build();
	}

	private static String prefix(TestInfo test) {
		return "/auth/" + keyspace.incrementAndGet() + "-" + test.getTestMethod().orElseThrow().getName() + "/";
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
