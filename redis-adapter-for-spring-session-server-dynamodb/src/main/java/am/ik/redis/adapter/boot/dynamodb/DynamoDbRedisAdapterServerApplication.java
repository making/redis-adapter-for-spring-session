package am.ik.redis.adapter.boot.dynamodb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the adapter server that keeps its sessions in DynamoDB.
 *
 * <p>
 * Where DynamoDB is and how requests are signed are Spring Cloud AWS's ordinary
 * {@code spring.cloud.aws.*} properties — on AWS the default provider chain usually needs
 * nothing at all, and a local run points the endpoint at an emulator:
 *
 * <pre>{@code
 * java -jar redis-adapter-for-spring-session-server-dynamodb-<version>-exec.jar \
 *     --spring.cloud.aws.region.static=ap-northeast-1
 * }</pre>
 *
 * Everything else is configured through
 * {@link am.ik.redis.adapter.boot.RedisAdapterProperties} and
 * {@link DynamoDbBackendProperties}, so the usual Spring Boot sources apply — a command
 * line argument, an {@code application.properties} beside the jar, or the environment
 * variables a container platform hands over:
 *
 * <pre>{@code
 * REDIS_ADAPTER_PORT=16379
 * REDIS_ADAPTER_PASSWORD=s3cret
 * SPRING_CLOUD_AWS_REGION_STATIC=ap-northeast-1
 * REDIS_ADAPTER_DYNAMODB_TABLE_NAME=redis-adapter
 * }</pre>
 *
 * <p>
 * The table is created when it is absent (on-demand billing), so the one thing the IAM
 * principal needs beyond the item calls is {@code CreateTable}/{@code DescribeTable}/
 * {@code UpdateTimeToLive} — or pre-create the table and set
 * {@code redis-adapter.dynamodb.create-table=false}.
 *
 * <p>
 * The application also serves {@code /actuator/health} and the metrics of whatever
 * registry is on its classpath over HTTP, on the usual Spring Boot port — that port
 * carries nothing but the actuator.
 *
 * <h2>Running more than one</h2> This is a server for running several replicas behind a
 * load balancer. The sessions live in DynamoDB, so every replica serves the same ones and
 * they outlive all of them, and a key one replica removes reaches the clients of the
 * others through the polled key-event log — which is what an application's
 * session-expired event is made of. Unlike every other backend, requests here are billed:
 * {@code README.md} §dynamodb says what a save, the poll and the sweep cost.
 */
@SpringBootApplication
public class DynamoDbRedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DynamoDbRedisAdapterServerApplication.class, args);
	}

}
