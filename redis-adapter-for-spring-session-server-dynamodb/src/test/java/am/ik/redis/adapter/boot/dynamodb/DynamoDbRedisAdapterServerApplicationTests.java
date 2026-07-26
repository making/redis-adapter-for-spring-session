package am.ik.redis.adapter.boot.dynamodb;

import java.util.UUID;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped application, as shipped: the {@code @SpringBootApplication} whose component
 * scan finds the backend configuration, the server auto-configuration arriving from the
 * server module, and the client built by Spring Cloud AWS — here pointed at the emulator,
 * which is exactly how a local run of this server is pointed at one.
 */
@SpringBootTest(classes = DynamoDbRedisAdapterServerApplication.class)
class DynamoDbRedisAdapterServerApplicationTests {

	@DynamicPropertySource
	static void dynamoDb(DynamicPropertyRegistry properties) {
		properties.add("spring.cloud.aws.dynamodb.endpoint", () -> Floci.running().getEndpoint());
		properties.add("spring.cloud.aws.region.static", () -> Floci.running().getRegion());
		properties.add("spring.cloud.aws.credentials.access-key", () -> Floci.running().getAccessKey());
		properties.add("spring.cloud.aws.credentials.secret-key", () -> Floci.running().getSecretKey());
		properties.add("redis-adapter.dynamodb.table-name", () -> "app-" + UUID.randomUUID());
	}

	@Autowired
	private KeyValueStores databases;

	@Test
	void theApplicationServesTheDynamoDbBackend() {
		assertThat(this.databases.databases()).hasSize(1)
			.allSatisfy(store -> assertThat(store).isInstanceOf(DynamoDbKeyValueStore.class));
	}

}
