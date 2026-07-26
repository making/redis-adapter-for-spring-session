package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.redis.adapter.server.RedisAdapterServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the settings the README shows, rather than only reading them.
 *
 * <p>
 * Each example is loaded from the file it is quoted from, a real adapter is started from
 * it, and a real Redis client is configured from it — because a property name that is
 * subtly wrong binds to nothing and changes nothing, which is precisely the mistake a
 * documented example is supposed to save a reader from.
 *
 * <p>
 * Three values in the examples cannot be the ones a test uses: the address and port the
 * adapter listens on, and where the certificate material is. Those are substituted here,
 * by key, and the substitution fails if the key it names is no longer in the example.
 * Everything else is used exactly as it is written.
 *
 * <p>
 * These are the settings every adapter server takes, so the server runs on this module's
 * own test backend. What a backend is given is run the same way, in that backend's server
 * module, against that backend.
 */
class ReadmeConfigurationExamplesTests {

	private static final String APPLICATION = "readme/application.properties";

	private static final String SERVER = "readme/server.properties";

	private static final String ENVIRONMENT = "readme/server.env";

	@TempDir
	private Path certificates;

	/**
	 * The quick start: a server as it is shipped, and an application that names where it
	 * listens.
	 */
	@Test
	void theConnectionExamplePointsAnApplicationAtTheAdapter() {
		adapter(Map.of("redis-adapter.bind-address", "127.0.0.1", "redis-adapter.port", "0")).run(server -> {
			Map<String, String> connection = substituted(settings(APPLICATION, "app-connection"), address(server));

			client(connection).run(application -> assertThat(ping(application)).isEqualTo("PONG"));
		});
	}

	/**
	 * The server settings, used as they are written, with a client that answers the
	 * password they ask for. A client that does not is refused, which is what says the
	 * password was read rather than merely bound.
	 */
	@Test
	void theServerSettingsExampleServesTheClientThatAnswersItsPassword() {
		Map<String, String> settings = settings(SERVER, "server-settings");

		adapter(substituted(settings, Map.of("redis-adapter.bind-address", "127.0.0.1", "redis-adapter.port", "0")))
			.run(server -> {
				assertThat(server.getBean(KeyValueStores.class).databases()).hasSize(1);

				Map<String, String> connection = substituted(settings(APPLICATION, "app-connection"), address(server));
				client(connection).run(application -> assertThatThrownBy(() -> ping(application))
					.isInstanceOf(DataAccessException.class));

				client(merged(connection, settings(APPLICATION, "app-password")))
					.run(application -> assertThat(ping(application)).isEqualTo("PONG"));
			});
	}

	/**
	 * The same settings as the environment a container platform hands over. They are
	 * bound as an environment rather than as properties, since the point of the example
	 * is the spelling: a variable that misses by one underscore reaches nothing and is
	 * never complained about, so an operator's setting is silently the default.
	 */
	@Test
	void theEnvironmentExampleConfiguresTheServerThroughEnvironmentVariables() {
		Map<String, Object> environment = new HashMap<>(settings(ENVIRONMENT, "server-env"));

		new ApplicationContextRunner().withUserConfiguration(ServerProperties.class)
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
			.run(context -> assertThat(context.getBean(RedisAdapterProperties.class)).satisfies(properties -> {
				assertThat(properties.port()).isEqualTo(16379);
				assertThat(properties.password()).isEqualTo("s3cret");
				assertThat(properties.databases()).isEqualTo(16);
			}));
	}

	/**
	 * The two halves of the TLS example, used together: the server serves the bundle it
	 * is given and the application trusts it. Only the file names are substituted, for
	 * material a test can read.
	 * @throws IOException if the certificate material cannot be copied
	 */
	@Test
	void theTlsExamplesGetAnApplicationOntoAnEncryptedPort() throws IOException {
		install("server.crt", "server.key", "ca.crt");
		Map<String, String> server = substituted(settings(SERVER, "server-tls"),
				Map.of("spring.ssl.bundle.pem.adapter.keystore.certificate", file("server.crt"),
						"spring.ssl.bundle.pem.adapter.keystore.private-key", file("server.key")));

		adapter(merged(Map.of("redis-adapter.bind-address", "127.0.0.1", "redis-adapter.port", "0"), server))
			.run(context -> {
				Map<String, String> application = substituted(settings(APPLICATION, "app-tls"),
						Map.of("spring.ssl.bundle.pem.adapter.truststore.certificate", file("ca.crt")));

				client(merged(substituted(settings(APPLICATION, "app-connection"), address(context)), application))
					.run(encrypted -> assertThat(ping(encrypted)).isEqualTo("PONG"));
			});
	}

	/**
	 * The adapter, configured with the settings it is given.
	 * @param settings what the operator wrote
	 * @return the runner
	 */
	private ApplicationContextRunner adapter(Map<String, String> settings) {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(SslAutoConfiguration.class, RedisAdapterServerAutoConfiguration.class))
			.withUserConfiguration(TestBackendConfiguration.class)
			.withPropertyValues(pairs(settings));
	}

	/**
	 * An application with nothing but a Redis client, configured with the settings it is
	 * given.
	 * @param settings what the application wrote
	 * @return the runner
	 */
	private ApplicationContextRunner client(Map<String, String> settings) {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, SslAutoConfiguration.class))
			.withPropertyValues(pairs(settings));
	}

	private static String ping(AssertableApplicationContext application) {
		try (RedisConnection connection = application.getBean(RedisConnectionFactory.class).getConnection()) {
			return connection.ping();
		}
	}

	/**
	 * Returns where the running adapter can actually be reached, as the connection
	 * example's own keys.
	 * @param server the context the adapter runs in
	 * @return the settings to substitute
	 */
	private static Map<String, String> address(AssertableApplicationContext server) {
		return Map.of("spring.data.redis.host", "127.0.0.1", "spring.data.redis.port",
				String.valueOf(server.getBean(RedisAdapterServer.class).port()));
	}

	private static Map<String, String> settings(String resource, String name) {
		return ReadmeSnippets.settings(resource, name);
	}

	/**
	 * Replaces the values of the given keys, which is only allowed for keys the example
	 * still has: a substitution that quietly adds a setting the README never showed would
	 * make the test pass for the wrong reason.
	 * @param settings the settings the example holds
	 * @param replacements the values to use instead
	 * @return the settings to run with
	 */
	private static Map<String, String> substituted(Map<String, String> settings, Map<String, String> replacements) {
		assertThat(settings).containsKeys(replacements.keySet().toArray(String[]::new));
		Map<String, String> substituted = new LinkedHashMap<>(settings);
		substituted.putAll(replacements);
		return substituted;
	}

	private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
		Map<String, String> merged = new LinkedHashMap<>(first);
		merged.putAll(second);
		return merged;
	}

	private static String[] pairs(Map<String, String> settings) {
		return settings.entrySet()
			.stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.toArray(String[]::new);
	}

	private void install(String... names) throws IOException {
		for (String name : names) {
			try (InputStream source = new ClassPathResource("tls/" + name).getInputStream()) {
				Files.copy(source, this.certificates.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private String file(String name) {
		return "file:" + this.certificates.resolve(name);
	}

	/**
	 * The server's settings, bound and nothing more. The environment example is about how
	 * a variable is spelt, so it must not start a server on the port it names.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RedisAdapterProperties.class)
	static class ServerProperties {

	}

}
