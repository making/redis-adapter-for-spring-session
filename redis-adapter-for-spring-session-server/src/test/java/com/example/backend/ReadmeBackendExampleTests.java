package com.example.backend;

import java.time.Duration;

import am.ik.redis.adapter.boot.KeyValueStoreConfiguration;
import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import am.ik.redis.adapter.boot.RedisAdapterServerConfiguration;
import am.ik.redis.adapter.server.RedisAdapterServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the backend example the README shows: a module contributing one
 * {@link MyKeyValueStoreFactory} bean, selected by the name it answers to.
 *
 * <p>
 * A real client writes through the running server, so what is asserted is that the
 * sessions of an application would land in the backend that was chosen — not merely that
 * a bean of the right type exists. Two databases are served, since a backend has to keep
 * them apart and the README says so.
 */
class ReadmeBackendExampleTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(KeyValueStoreConfiguration.class, RedisAdapterServerConfiguration.class,
				MyBackendConfiguration.class)
		.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0", "redis-adapter.databases=2")
		// The one setting that selects this backend is the README's own.
		.withPropertyValues(ReadmeSnippets.settingPairs("readme/server.properties", "backend-selection"));

	@Test
	void theBackendExampleTakesOverFromTheBundledOne() {
		this.runner.run(context -> {
			KeyValueStores databases = context.getBean(KeyValueStores.class);
			assertThat(databases.databases()).hasSize(2)
				.allSatisfy(store -> assertThat(store).isInstanceOf(MyKeyValueStore.class));
			assertThat(databases.databases()).extracting(store -> ((MyKeyValueStore) store).databaseIndex())
				.containsExactly(0, 1);

			int port = context.getBean(RedisAdapterServer.class).port();
			write(port, 1, "spring:session:sessions:example");

			assertThat(databases.database(1).exists("spring:session:sessions:example".getBytes(UTF_8))).isTrue();
			assertThat(databases.database(0).exists("spring:session:sessions:example".getBytes(UTF_8))).isFalse();
		});
	}

	/**
	 * Writes a key through the server, over the client an application would use.
	 * @param port where the adapter listens
	 * @param database the database to write to
	 * @param key the key to create
	 */
	private static void write(int port, int database, String key) {
		RedisURI uri = RedisURI.builder()
			.withHost("127.0.0.1")
			.withPort(port)
			.withDatabase(database)
			.withTimeout(Duration.ofSeconds(10))
			.build();
		RedisClient client = RedisClient.create();
		try (StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8, uri)) {
			connection.sync().hset(key, "creationTime", "0");
		}
		finally {
			client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		}
	}

}
