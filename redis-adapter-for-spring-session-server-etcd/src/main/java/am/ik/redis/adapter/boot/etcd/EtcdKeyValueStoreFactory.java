package am.ik.redis.adapter.boot.etcd;

import javax.net.ssl.SSLContext;

import am.ik.redis.adapter.boot.KeyValueStoreFactory;
import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;

/**
 * The etcd backend: one keyspace of an etcd cluster per database, shared by every adapter
 * pointed at it.
 *
 * <p>
 * This is the backend a horizontally scaled deployment uses. Several adapters serve the
 * same sessions, the sessions outlive every adapter, and a key one adapter expires is
 * announced to the clients connected to all of them.
 *
 * <p>
 * Nothing is connected to until {@link #create(int)} is called, which is what keeps a
 * misconfigured endpoint a failure of the first session rather than of bean creation. The
 * TLS bundle is resolved there for the same reason.
 *
 * @param properties where etcd is and how to talk to it
 * @param sslBundles the SSL bundles an {@code https://} endpoint's certificate material
 * comes from, which need not exist when no bundle is named
 */
public record EtcdKeyValueStoreFactory(EtcdBackendProperties properties,
		ObjectProvider<SslBundles> sslBundles) implements KeyValueStoreFactory {

	/** The name this backend is known by, in the logs and in the documentation. */
	public static final String NAME = "etcd";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return EtcdKeyValueStore.builder()
			.endpoints(this.properties.endpoints())
			.keyPrefix(this.properties.keyPrefix(databaseIndex))
			.connectTimeout(this.properties.connectTimeout())
			.requestTimeout(this.properties.requestTimeout())
			.watchRetryDelay(this.properties.watchRetryDelay())
			.credentials(this.properties.username(), this.properties.password())
			.sslContext(sslContext())
			.build();
	}

	/**
	 * Returns the TLS context to reach etcd with.
	 * @return the context, or {@code null} when no bundle is named and the JDK's default
	 * trust material is used
	 * @throws IllegalStateException if a bundle is named but no bundles are configured,
	 * rather than quietly reaching etcd with the wrong trust material
	 */
	private @Nullable SSLContext sslContext() {
		String bundle = this.properties.sslBundle();
		if (bundle == null) {
			return null;
		}
		SslBundles bundles = this.sslBundles.getIfAvailable();
		if (bundles == null) {
			throw new IllegalStateException("redis-adapter.etcd.ssl-bundle names " + bundle
					+ " but this application has no SSL bundles configured");
		}
		return bundles.getBundle(bundle).createSslContext();
	}

}
