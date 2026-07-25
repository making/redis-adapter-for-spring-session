package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.server.RedisAdapterServer;
import am.ik.redis.adapter.store.KeyValueStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.TestSocketUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers what the configuration properties do to the running server. Every case starts a
 * real server and, where the answer is only visible on the wire, drives it with a real
 * Redis client: a property that binds but changes nothing would pass any assertion made
 * on the properties themselves.
 *
 * <p>
 * The transport-security properties are covered on their own, in
 * {@link RedisAdapterServerTlsConfigurationTests}.
 */
class RedisAdapterServerConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(KeyValueStoreConfiguration.class, RedisAdapterServerConfiguration.class)
		.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0");

	@Test
	void servesOneInMemoryDatabaseByDefault() {
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(KeyValueStores.class).hasSingleBean(RedisAdapterServer.class);
			assertThat(context.getBean(KeyValueStores.class).databases()).hasSize(1)
				.allSatisfy(store -> assertThat(store).isInstanceOf(InMemoryKeyValueStore.class));
			assertThat(context.getBean(RedisAdapterServer.class).isRunning()).isTrue();
		});
	}

	@Test
	void bindsThePortItIsGiven() {
		int port = TestSocketUtils.findAvailableTcpPort();

		this.runner.withPropertyValues("redis-adapter.port=" + port).run(context -> {
			assertThat(context.getBean(RedisAdapterServer.class).port()).isEqualTo(port);
			assertThat(ping(port)).isEqualTo("PONG");
		});
	}

	/**
	 * The address is proven by giving the server one it cannot have: a documentation
	 * address is on no interface anywhere, so a server that reached the socket with it
	 * fails to bind, and one that ignored the property would come up on every interface
	 * instead.
	 */
	@Test
	void bindsTheAddressItIsGiven() {
		this.runner.withPropertyValues("redis-adapter.bind-address=192.0.2.1")
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause().isInstanceOf(BindException.class));
	}

	/**
	 * A port that is already taken stops the application there and then. A process that
	 * came up and served nobody would be worse: the failure would only be found by the
	 * application whose sessions it was supposed to hold.
	 */
	@Test
	void refusesToStartWhenItsPortIsTaken() throws IOException {
		try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			this.runner.withPropertyValues("redis-adapter.port=" + taken.getLocalPort())
				.run(context -> assertThat(context).hasFailed()
					.getFailure()
					.rootCause()
					.isInstanceOf(BindException.class));
		}
	}

	/**
	 * A closed context must leave nothing bound, or a server could not be restarted in
	 * place. The same port is taken twice in a row to prove it.
	 */
	@Test
	void releasesItsPortWhenTheContextCloses() {
		int port = TestSocketUtils.findAvailableTcpPort();
		ApplicationContextRunner onAFixedPort = this.runner.withPropertyValues("redis-adapter.port=" + port);

		onAFixedPort.run(context -> assertThat(ping(port)).isEqualTo("PONG"));

		onAFixedPort.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(ping(port)).isEqualTo("PONG");
		});
	}

	/**
	 * Each database is a keyspace of its own, backed by a store of its own, and
	 * {@code SELECT} is what picks between them.
	 */
	@Test
	void servesAsManyDatabasesAsItIsAskedFor() {
		this.runner.withPropertyValues("redis-adapter.databases=2").run(context -> {
			KeyValueStores databases = context.getBean(KeyValueStores.class);
			assertThat(databases.databases()).hasSize(2);

			int port = context.getBean(RedisAdapterServer.class).port();
			connected(uri(port).withDatabase(1).build(), connection -> connection.sync().append("key", "value"));

			assertThat(databases.database(1).exists("key".getBytes(UTF_8))).isTrue();
			assertThat(databases.database(0).exists("key".getBytes(UTF_8))).isFalse();
		});
	}

	@Test
	void requiresThePasswordItIsGiven() {
		this.runner.withPropertyValues("redis-adapter.password=s3cret").run(context -> {
			int port = context.getBean(RedisAdapterServer.class).port();

			assertThatThrownBy(() -> ping(port)).isInstanceOf(RedisConnectionException.class);
			String reply = connected(uri(port).withPassword("s3cret".toCharArray()).build(),
					connection -> connection.sync().ping());
			assertThat(reply).isEqualTo("PONG");
		});
	}

	@Test
	void refusesToStartWhenTheBackendIsUnknown() {
		this.runner.withPropertyValues("redis-adapter.backend=nowhere")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("No backend answers to redis-adapter.backend=nowhere; this server has [etcd, in-memory]"));
	}

	/**
	 * The bundled backend is not privileged. A module that contributes a factory under
	 * its own name takes over, and the server writes to it — which is the whole of what a
	 * future backend has to do to be used.
	 *
	 * <p>
	 * Both backends are registered in both runs and only the property differs, so the
	 * choice is made from the name when the application starts rather than from which
	 * factory happens to be on the classpath. That is what keeps it a choice at all in an
	 * ahead-of-time compiled image, where a condition on the bean would have been decided
	 * while the image was built.
	 */
	@Test
	void picksTheBackendThatAnswersToTheConfiguredName() {
		ApplicationContextRunner withBothBackends = this.runner.withUserConfiguration(FakeBackendConfiguration.class);

		withBothBackends.withPropertyValues("redis-adapter.backend=fake").run(context -> {
			KeyValueStores databases = context.getBean(KeyValueStores.class);
			assertThat(databases.database(0)).isInstanceOf(RecordingKeyValueStore.class);

			int port = context.getBean(RedisAdapterServer.class).port();
			connected(uri(port).build(), connection -> connection.sync().append("key", "value"));

			assertThat(databases.database(0).exists("key".getBytes(UTF_8))).isTrue();
		});

		withBothBackends.withPropertyValues("redis-adapter.backend=in-memory")
			.run(context -> assertThat(context.getBean(KeyValueStores.class).database(0))
				.isInstanceOf(InMemoryKeyValueStore.class));
	}

	/**
	 * Two backends under one name is a question with no right answer: picking either
	 * would leave nobody able to say where the sessions went.
	 */
	@Test
	void refusesToStartWhenTwoBackendsAnswerToTheSameName() {
		this.runner.withUserConfiguration(FakeBackendConfiguration.class, SecondFakeBackendConfiguration.class)
			.withPropertyValues("redis-adapter.backend=fake")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("More than one backend answers to redis-adapter.backend=fake"));
	}

	/**
	 * Every backend is closed when the context goes, whichever database it serves, and
	 * each of them was created for the database it holds. A backend that keeps a thread —
	 * the bundled one runs an expiry sweeper — would otherwise outlive the application
	 * that asked for it.
	 */
	@Test
	void closesEveryBackendWhenTheContextCloses() {
		List<RecordingKeyValueStore> stores = new ArrayList<>();

		this.runner.withUserConfiguration(FakeBackendConfiguration.class)
			.withPropertyValues("redis-adapter.backend=fake", "redis-adapter.databases=2")
			.run(context -> {
				stores.addAll(context.getBean(KeyValueStores.class)
					.databases()
					.stream()
					.map(RecordingKeyValueStore.class::cast)
					.toList());
				assertThat(stores).extracting(RecordingKeyValueStore::databaseIndex).containsExactly(0, 1);
				assertThat(stores).noneMatch(RecordingKeyValueStore::isClosed);
			});

		assertThat(stores).hasSize(2).allMatch(RecordingKeyValueStore::isClosed);
	}

	private static String ping(int port) {
		return connected(uri(port).build(), connection -> connection.sync().ping());
	}

	private static RedisURI.Builder uri(int port) {
		return RedisURI.builder().withHost("127.0.0.1").withPort(port).withTimeout(Duration.ofSeconds(10));
	}

	/**
	 * Runs an action on a live connection to the adapter, over the same client an
	 * application would use.
	 * @param uri where and how to connect
	 * @param action what to do with the connection
	 * @return whatever the action returned
	 */
	private static <T> T connected(RedisURI uri, Function<StatefulRedisConnection<String, String>, T> action) {
		RedisClient client = RedisClient.create();
		try (StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8, uri)) {
			return action.apply(connection);
		}
		finally {
			client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		}
	}

	/**
	 * A backend module, as one written outside this project would look: a single factory
	 * bean, naming the backend it is.
	 */
	@Configuration(proxyBeanMethods = false)
	static class FakeBackendConfiguration {

		@Bean
		KeyValueStoreFactory fakeKeyValueStoreFactory() {
			return new FakeBackend();
		}

	}

	/**
	 * A second module claiming a name the first one already answers to.
	 */
	@Configuration(proxyBeanMethods = false)
	static class SecondFakeBackendConfiguration {

		@Bean
		KeyValueStoreFactory anotherFakeKeyValueStoreFactory() {
			return new FakeBackend();
		}

	}

	/**
	 * The factory of the fake backend, handing out a store per database that records what
	 * it was created for and when it was closed.
	 */
	record FakeBackend() implements KeyValueStoreFactory {

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public KeyValueStore create(int databaseIndex) {
			return new RecordingKeyValueStore(databaseIndex);
		}

	}

}
