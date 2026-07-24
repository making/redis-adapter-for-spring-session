package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.function.Consumer;

import am.ik.redis.adapter.server.RedisAdapterServer;
import am.ik.redis.adapter.store.KeyValueStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a stock Spring Session application against the adapter over {@code rediss://},
 * with both ends configured the Spring Boot way: the server's certificate and key come
 * from a {@code spring.ssl.bundle.pem.*}, and so does the client's trust material,
 * through {@code spring.data.redis.ssl.bundle}. Nothing here loads a keystore by hand,
 * which is the point — an operator configures TLS here exactly as they would against a
 * real Redis.
 *
 * <p>
 * The client also presents a certificate of its own and the server is set to demand one,
 * so a green run means the handshake was mutual and the session round trip ran inside it.
 * The certificates are the test material under {@code src/test/resources/tls}; see
 * {@link RedisAdapterServerTlsConfigurationTests} for what each of them is.
 */
@SpringBootTest(classes = TlsSessionEndToEndTests.SessionApplication.class,
		properties = { "redis-adapter.ssl.bundle=adapter-server", "redis-adapter.ssl.client-auth=need",
				"spring.ssl.bundle.pem.adapter-server.keystore.certificate=classpath:tls/server.crt",
				"spring.ssl.bundle.pem.adapter-server.keystore.private-key=classpath:tls/server.key",
				"spring.ssl.bundle.pem.adapter-server.truststore.certificate=classpath:tls/ca.crt",
				"spring.data.redis.ssl.enabled=true", "spring.data.redis.ssl.bundle=adapter-client",
				"spring.ssl.bundle.pem.adapter-client.keystore.certificate=classpath:tls/client.crt",
				"spring.ssl.bundle.pem.adapter-client.keystore.private-key=classpath:tls/client.key",
				"spring.ssl.bundle.pem.adapter-client.truststore.certificate=classpath:tls/ca.crt" })
class TlsSessionEndToEndTests {

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private RedisAdapterServer server;

	@Test
	void aSessionSurvivesTheRoundTripOverTls() {
		Session saved = create(this.sessions, session -> session.setAttribute("user", "alice"));

		Session loaded = this.sessions.findById(saved.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("user")).isEqualTo("alice");
		assertThat(store().exists(SessionKeys.DEFAULT.session(saved.getId()))).isTrue();
	}

	/**
	 * The encryption is the port's, not the client's. A client that speaks plain RESP to
	 * a TLS port gets nothing, which is what proves the round trip above was encrypted
	 * rather than merely successful.
	 */
	@Test
	void aPlainClientIsNotServed() {
		RedisURI uri = RedisURI.builder()
			.withHost("127.0.0.1")
			.withPort(this.server.port())
			.withTimeout(Duration.ofSeconds(10))
			.build();
		RedisClient client = RedisClient.create();
		try {
			assertThatThrownBy(() -> {
				try (StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8, uri)) {
					connection.sync().ping();
				}
			}).isInstanceOf(RedisException.class);
		}
		finally {
			client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		}
	}

	/**
	 * Returns the backend the application's sessions land in, which is the first
	 * database.
	 * @return the backend of database 0
	 */
	private KeyValueStore store() {
		return this.databases.database(0);
	}

	/**
	 * Creates, customizes and saves a session. The repository's session type is not
	 * visible from here, so it is captured as a type variable rather than named.
	 * @param repository the session repository
	 * @param customizer what to set on the new session
	 * @return the saved session
	 */
	private static <S extends Session> Session create(SessionRepository<S> repository, Consumer<Session> customizer) {
		S session = repository.createSession();
		customizer.accept(session);
		repository.save(session);
		return session;
	}

	@EnableAutoConfiguration
	@EnableRedisHttpSession
	@Import(AdapterServerTestConfiguration.class)
	static class SessionApplication {

	}

}
