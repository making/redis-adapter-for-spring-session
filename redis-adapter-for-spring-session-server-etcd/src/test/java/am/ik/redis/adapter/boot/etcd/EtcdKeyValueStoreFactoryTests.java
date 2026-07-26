package am.ik.redis.adapter.boot.etcd;

import java.time.Duration;
import java.util.List;

import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the etcd backend contributes to the server, without an etcd.
 *
 * <p>
 * The end-to-end behaviour is covered against a real cluster by
 * {@link EtcdBackendEndToEndTests}. What is worth asserting without one is the part a
 * deployment gets wrong: that the databases end up in keyspaces of their own, and that a
 * server which is <em>not</em> using etcd is not made to fail — or made to wait — by a
 * factory it never asked for.
 */
class EtcdKeyValueStoreFactoryTests {

	private static final EtcdBackendProperties PROPERTIES = new EtcdBackendProperties(
			List.of("http://etcd.invalid:2379"), "/sessions/", Duration.ofSeconds(5), Duration.ofSeconds(5),
			Duration.ofSeconds(1), null, null, null);

	@Test
	void answersToTheNameTheServerLogs() {
		assertThat(factory(PROPERTIES).name()).isEqualTo("etcd");
	}

	/**
	 * The factory is built while the application is still starting and is asked for its
	 * stores afterwards, so one that connected as it was built would turn an etcd that is
	 * briefly unreachable into an application that never comes up.
	 */
	@Test
	void connectsToNothingUntilItIsAskedForAStore() {
		assertThatCode(() -> factory(PROPERTIES)).doesNotThrowAnyException();
	}

	/**
	 * Each database is an independent keyspace, which for etcd means a prefix of its own
	 * under the one an operator gave.
	 */
	@Test
	void givesEachDatabaseAKeyspaceOfItsOwn() {
		assertThat(PROPERTIES.keyPrefix(0)).isEqualTo("/sessions/0/");
		assertThat(PROPERTIES.keyPrefix(1)).isEqualTo("/sessions/1/");
		assertThat(PROPERTIES.keyPrefix(11)).isEqualTo("/sessions/11/");
	}

	@Test
	void aPrefixWithoutASeparatorGetsOne() {
		assertThat(properties("/sessions").keyPrefix()).isEqualTo("/sessions/");
		assertThat(properties("/sessions").keyPrefix(1)).isEqualTo("/sessions/1/");
	}

	/**
	 * The prefix is what keeps one database out of another, so it has to be something. A
	 * blank one would put every database, and every deployment sharing the cluster, in
	 * the same keyspace.
	 */
	@Test
	void aBlankPrefixIsRefused() {
		assertThatThrownBy(() -> properties(" ")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.etcd.key-prefix");
	}

	@Test
	void anEmptyEndpointListIsRefused() {
		assertThatThrownBy(() -> new EtcdBackendProperties(List.of(), "/sessions/", Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofSeconds(1), null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.etcd.endpoints");
	}

	@Test
	void aTimeoutThatIsNotPositiveIsRefused() {
		assertThatThrownBy(() -> new EtcdBackendProperties(List.of("http://localhost:2379"), "/sessions/",
				Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(1), null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.etcd.connect-timeout");
	}

	/**
	 * A named bundle that does not exist must be said out loud rather than quietly
	 * replaced by the JDK's default trust material, which would reach etcd over a
	 * certificate nobody chose.
	 */
	@Test
	void aNamedBundleWithNoBundlesConfiguredIsRefusedWhenTheStoreIsCreated() {
		EtcdKeyValueStoreFactory factory = factory(new EtcdBackendProperties(List.of("https://etcd.invalid:2379"),
				"/sessions/", Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1), null, null, "etcd"));

		assertThatThrownBy(() -> factory.create(0)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("redis-adapter.etcd.ssl-bundle names etcd");
	}

	/**
	 * The store is built from the properties without reaching etcd, since a factory is
	 * asked for one store per database while the application is still starting. An
	 * endpoint that does not resolve is therefore not an error until something is stored.
	 */
	@Test
	void createsAStoreForAnEndpointItCannotReach() {
		try (EtcdKeyValueStore store = (EtcdKeyValueStore) factory(PROPERTIES).create(2)) {
			assertThat(store.keyPrefix()).isEqualTo("/sessions/2/");
		}
	}

	private static EtcdKeyValueStoreFactory factory(EtcdBackendProperties properties) {
		// A bean factory with no SslBundles in it, which is what an application that
		// configured none hands the factory.
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		return new EtcdKeyValueStoreFactory(properties, beans.getBeanProvider(SslBundles.class));
	}

	private static EtcdBackendProperties properties(String keyPrefix) {
		return new EtcdBackendProperties(List.of("http://localhost:2379"), keyPrefix, Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofSeconds(1), null, null, null);
	}

}
