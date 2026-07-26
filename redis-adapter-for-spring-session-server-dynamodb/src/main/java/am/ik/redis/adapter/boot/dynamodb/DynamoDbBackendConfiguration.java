package am.ik.redis.adapter.boot.dynamodb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Declares the backend this server keeps its sessions in.
 *
 * <p>
 * It is the whole of what a backend module wires up: the properties it is tuned with, and
 * the one factory bean the server asks for a store per database. The
 * {@link DynamoDbClient} arrives from Spring Cloud AWS's auto-configuration, so where
 * DynamoDB is and how requests are signed are {@code spring.cloud.aws.*} properties.
 * Everything else — the port, the lifecycle, the actuator — comes from the server
 * module's auto-configuration and is the same whichever backend is underneath.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbBackendProperties.class)
public class DynamoDbBackendConfiguration {

	/**
	 * Registers the DynamoDB backend.
	 * @param properties what the backend is tuned with
	 * @param client the client Spring Cloud AWS built from the deployment's region,
	 * credentials and endpoint settings
	 * @return the factory of this server's backend
	 */
	@Bean
	public DynamoDbKeyValueStoreFactory dynamoDbKeyValueStoreFactory(DynamoDbBackendProperties properties,
			DynamoDbClient client) {
		return new DynamoDbKeyValueStoreFactory(properties, client);
	}

}
