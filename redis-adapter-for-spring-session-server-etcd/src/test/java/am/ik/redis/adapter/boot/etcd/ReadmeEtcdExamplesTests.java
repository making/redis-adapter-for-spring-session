package am.ik.redis.adapter.boot.etcd;

import java.util.HashMap;
import java.util.Map;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import am.ik.redis.adapter.boot.RedisAdapterServerAutoConfiguration;
import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
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
 * Nothing here connects: the examples name a cluster that is not there, and what matters
 * is that the store the server built is pointed at it. What that store then does against
 * a real etcd is {@link EtcdBackendEndToEndTests}.
 */
class ReadmeEtcdExamplesTests {

	private static final String SETTINGS = "readme/server-etcd.properties";

	private static final String ENVIRONMENT = "readme/server-etcd.env";

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property this backend binds and invents none.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyThisBackendBinds() {
		assertThat(ReadmeSnippets.documentedProperties("properties:redis-adapter.etcd"))
			.containsExactlyInAnyOrderElementsOf(
					ReadmeSnippets.boundProperties("redis-adapter.etcd", EtcdBackendProperties.class));
	}

	@Test
	void theExamplePointsTheBackendAtTheClusterItNames() {
		Map<String, String> settings = ReadmeSnippets.settings(SETTINGS, "server-etcd");

		adapter().withPropertyValues(pairs(settings)).run(context -> {
			assertThat(context.getBean(EtcdBackendProperties.class)).satisfies(etcd -> {
				assertThat(etcd.endpoints()).containsExactly("http://etcd-0:2379", "http://etcd-1:2379",
						"http://etcd-2:2379");
				assertThat(etcd.keyPrefix(0)).isEqualTo("/redis-adapter/0/");
			});
			assertThat(context.getBean(KeyValueStores.class).databases()).hasSize(1)
				.allSatisfy(store -> assertThat(store).isInstanceOf(EtcdKeyValueStore.class));
		});
	}

	/**
	 * The same settings as the environment a container platform hands over. They are
	 * bound as an environment rather than as properties, since the point of the example
	 * is the spelling: a variable that misses by one underscore reaches nothing and is
	 * never complained about, so an operator's setting is silently the default. A list
	 * from one variable is the shape a platform can hand over and the one most likely to
	 * be got wrong.
	 */
	@Test
	void theEnvironmentExampleConfiguresTheBackendThroughEnvironmentVariables() {
		Map<String, Object> environment = new HashMap<>(ReadmeSnippets.settings(ENVIRONMENT, "server-etcd-env"));

		adapter()
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
			.run(context -> assertThat(context.getBean(EtcdBackendProperties.class)).satisfies(etcd -> {
				assertThat(etcd.endpoints()).containsExactly("http://etcd-0:2379", "http://etcd-1:2379");
				assertThat(etcd.keyPrefix(0)).isEqualTo("/redis-adapter/0/");
			}));
	}

	/**
	 * This server, as it is shipped, on a port a test may have.
	 * @return the runner
	 */
	private static ApplicationContextRunner adapter() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RedisAdapterServerAutoConfiguration.class))
			.withUserConfiguration(EtcdBackendConfiguration.class)
			.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0");
	}

	private static String[] pairs(Map<String, String> settings) {
		return settings.entrySet()
			.stream()
			.map(setting -> setting.getKey() + "=" + setting.getValue())
			.toArray(String[]::new);
	}

}
