package am.ik.redis.adapter.boot.etcd;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the backend this server keeps its sessions in.
 *
 * <p>
 * It is the whole of what a backend module wires up: the properties it is tuned with, and
 * the one factory bean the server asks for a store per database. Everything else — the
 * port, the lifecycle, the actuator — comes from the server module's auto-configuration
 * and is the same whichever backend is underneath.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EtcdBackendProperties.class)
public class EtcdBackendConfiguration {

	/**
	 * Registers the etcd backend.
	 * @param properties where etcd is and how to talk to it
	 * @param sslBundles the bundles a TLS-protected etcd is reached with, which need not
	 * exist when no bundle is named
	 * @return the factory of this server's backend
	 */
	@Bean
	public EtcdKeyValueStoreFactory etcdKeyValueStoreFactory(EtcdBackendProperties properties,
			ObjectProvider<SslBundles> sslBundles) {
		return new EtcdKeyValueStoreFactory(properties, sslBundles);
	}

}
