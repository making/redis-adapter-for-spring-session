package am.ik.redis.adapter.boot.foundationdb;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import am.ik.redis.adapter.boot.RedisAdapterServerAutoConfiguration;
import am.ik.redis.adapter.foundationdb.FdbCluster;
import am.ik.redis.adapter.foundationdb.FoundationDbKeyValueStore;
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
 * Nothing here reaches a FoundationDB: the factory opens nothing until it is asked for a
 * store, and no store is asked for, so what is checked is that the server built from the
 * example is tuned the way the example says. What that store then does against a running
 * cluster is {@link FoundationDbBackendEndToEndTests}.
 */
class ReadmeFoundationDbExamplesTests {

	private static final String SETTINGS = "readme/server-foundationdb.properties";

	private static final String ENVIRONMENT = "readme/server-foundationdb.env";

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property this backend binds and invents none.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyThisBackendBinds() {
		assertThat(ReadmeSnippets.documentedProperties("properties:redis-adapter.foundationdb"))
			.containsExactlyInAnyOrderElementsOf(
					ReadmeSnippets.boundProperties("redis-adapter.foundationdb", FoundationDbBackendProperties.class));
	}

	@Test
	void theExampleTunesTheBackendTheWayItSays() {
		Map<String, String> settings = new LinkedHashMap<>(ReadmeSnippets.settings(SETTINGS, "server-foundationdb"));
		// The example names the path a FoundationDB install conventionally puts its
		// cluster file at, which a machine running the tests has no reason to have. Which
		// file is not what the example is about; the property name is, and it still has
		// to bind or the assertion below sees the default instead.
		String conventional = settings.put("redis-adapter.foundationdb.cluster-file", FdbCluster.clusterFile());
		assertThat(conventional).isEqualTo("/etc/foundationdb/fdb.cluster");

		adapter().withPropertyValues(pairs(settings)).run(context -> {
			assertThat(context.getBean(FoundationDbBackendProperties.class)).satisfies(foundationdb -> {
				assertThat(foundationdb.clusterFile()).isEqualTo(FdbCluster.clusterFile());
				assertThat(foundationdb.keyPrefix()).isEqualTo("/redis-adapter/");
			});
			assertThat(context.getBean(KeyValueStores.class).databases()).hasSize(1)
				.allSatisfy(store -> assertThat(store).isInstanceOf(FoundationDbKeyValueStore.class));
		});
	}

	/**
	 * The same settings as the environment a container platform hands over — and for this
	 * backend that means the cluster file's <em>contents</em>, since a platform that
	 * delivers configuration as variables has no file to name. They are bound as an
	 * environment rather than as properties, since the point of the example is the
	 * spelling: a variable that misses by one underscore reaches nothing and is never
	 * complained about, so an operator's setting is silently the default.
	 */
	@Test
	void theEnvironmentExampleConfiguresTheBackendThroughEnvironmentVariables() {
		Map<String, Object> environment = new HashMap<>(
				ReadmeSnippets.settings(ENVIRONMENT, "server-foundationdb-env"));

		adapter()
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
			.run(context -> assertThat(context.getBean(FoundationDbBackendProperties.class)).satisfies(foundationdb -> {
				assertThat(foundationdb.clusterFileContents())
					.isEqualTo("redis:adapter@fdb-0:4500,fdb-1:4500,fdb-2:4500");
				assertThat(foundationdb.keyPrefix()).isEqualTo("/redis-adapter/");
			}));
	}

	/**
	 * This server, as it is shipped, on a port a test may have — with the deadlines wound
	 * right down, since the environment example points at a cluster that is not there and
	 * a context should not spend its default five seconds finding that out twice.
	 * @return the runner
	 */
	private static ApplicationContextRunner adapter() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RedisAdapterServerAutoConfiguration.class))
			.withUserConfiguration(FoundationDbBackendConfiguration.class)
			.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0",
					"redis-adapter.foundationdb.transaction-timeout=500ms",
					"redis-adapter.foundationdb.watch-timeout=500ms");
	}

	private static String[] pairs(Map<String, String> settings) {
		return settings.entrySet()
			.stream()
			.map(setting -> setting.getKey() + "=" + setting.getValue())
			.toArray(String[]::new);
	}

}
