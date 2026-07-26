package am.ik.redis.adapter.boot.dynamodb;

import java.util.HashMap;
import java.util.Map;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import am.ik.redis.adapter.boot.RedisAdapterServerAutoConfiguration;
import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.dynamodb.DynamoDbAutoConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the README's account of this backend to what this backend actually binds.
 *
 * <p>
 * Each example is loaded from the file it is quoted from and a real server is built from
 * it, because a property name that is subtly wrong binds to nothing and changes nothing,
 * which is precisely the mistake a documented example is supposed to save a reader from.
 * That the README shows these files' regions verbatim is checked in the server module,
 * which is where the README's examples are collected.
 *
 * <p>
 * Nothing here reaches a DynamoDB: the runner overrides the endpoint with a port nothing
 * listens on, and what matters is that the server built from the example is tuned the way
 * the example says. What that store then does against a running store is
 * {@link DynamoDbBackendEndToEndTests}.
 */
class ReadmeDynamoDbExamplesTests {

	private static final String SETTINGS = "readme/server-dynamodb.properties";

	private static final String ENVIRONMENT = "readme/server-dynamodb.env";

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property this backend binds and invents none.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyThisBackendBinds() {
		assertThat(ReadmeSnippets.documentedProperties("properties:redis-adapter.dynamodb"))
			.containsExactlyInAnyOrderElementsOf(
					ReadmeSnippets.boundProperties("redis-adapter.dynamodb", DynamoDbBackendProperties.class));
	}

	@Test
	void theExampleTunesTheBackendTheWayItSays() {
		Map<String, String> settings = ReadmeSnippets.settings(SETTINGS, "server-dynamodb");

		adapter().withPropertyValues(pairs(settings)).run(context -> {
			assertThat(context.getBean(DynamoDbBackendProperties.class)).satisfies(dynamodb -> {
				assertThat(dynamodb.tableName()).isEqualTo("redis-adapter");
				assertThat(dynamodb.shards()).isEqualTo(4);
			});
			assertThat(context.getEnvironment().getProperty("spring.cloud.aws.region.static"))
				.isEqualTo("ap-northeast-1");
			assertThat(context.getBean(KeyValueStores.class).databases()).hasSize(1)
				.allSatisfy(store -> assertThat(store).isInstanceOf(DynamoDbKeyValueStore.class));
		});
	}

	/**
	 * The same settings as the environment a container platform hands over. They are
	 * bound as an environment rather than as properties, since the point of the example
	 * is the spelling: a variable that misses by one underscore reaches nothing and is
	 * never complained about, so an operator's setting is silently the default.
	 */
	@Test
	void theEnvironmentExampleConfiguresTheBackendThroughEnvironmentVariables() {
		Map<String, Object> environment = new HashMap<>(ReadmeSnippets.settings(ENVIRONMENT, "server-dynamodb-env"));

		adapter()
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
			.run(context -> assertThat(context.getBean(DynamoDbBackendProperties.class)).satisfies(dynamodb -> {
				assertThat(dynamodb.tableName()).isEqualTo("redis-adapter");
				assertThat(dynamodb.shards()).isEqualTo(4);
			}));
	}

	/**
	 * This server, as it is shipped, on a port a test may have — and pointed at a port
	 * nothing listens on, so no example ever sends a unit test to a real AWS endpoint.
	 * @return the runner
	 */
	private static ApplicationContextRunner adapter() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RedisAdapterServerAutoConfiguration.class,
					RegionProviderAutoConfiguration.class, CredentialsProviderAutoConfiguration.class,
					AwsAutoConfiguration.class, DynamoDbAutoConfiguration.class))
			.withUserConfiguration(DynamoDbBackendConfiguration.class)
			.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0",
					"spring.cloud.aws.dynamodb.endpoint=http://127.0.0.1:1",
					"spring.cloud.aws.credentials.access-key=test", "spring.cloud.aws.credentials.secret-key=test",
					"redis-adapter.dynamodb.request-timeout=250ms");
	}

	private static String[] pairs(Map<String, String> settings) {
		return settings.entrySet()
			.stream()
			.map(setting -> setting.getKey() + "=" + setting.getValue())
			.toArray(String[]::new);
	}

}
