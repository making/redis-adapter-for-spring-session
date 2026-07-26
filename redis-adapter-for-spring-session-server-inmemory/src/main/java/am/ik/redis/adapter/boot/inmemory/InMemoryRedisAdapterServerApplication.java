package am.ik.redis.adapter.boot.inmemory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the adapter server that keeps its sessions in this process's own memory.
 *
 * <p>
 * Run it as it is and it listens on 6379, which is all an application needs to have its
 * sessions served:
 *
 * <pre>{@code
 * java -jar redis-adapter-for-spring-session-server-inmemory-<version>-exec.jar
 * }</pre>
 *
 * Everything is configured through
 * {@link am.ik.redis.adapter.boot.RedisAdapterProperties} and
 * {@link InMemoryBackendProperties}, so the usual Spring Boot sources apply — a command
 * line argument, an {@code application.properties} beside the jar, or the environment
 * variables a container platform hands over:
 *
 * <pre>{@code
 * REDIS_ADAPTER_PORT=16379
 * REDIS_ADAPTER_PASSWORD=s3cret
 * REDIS_ADAPTER_DATABASES=16
 * }</pre>
 *
 * <p>
 * The application also serves {@code /actuator/health} and the metrics of whatever
 * registry is on its classpath over HTTP, on the usual Spring Boot port — that port
 * carries nothing but the actuator.
 *
 * <h2>Running more than one</h2> The adapter itself keeps no session state: everything it
 * is asked to remember goes to the backend. Several replicas behind a load balancer
 * therefore serve the same sessions, but only as far as the backend does — and this one
 * does not, since each replica owns its own map. Scaling horizontally means a server
 * built around a backend that is shared, and one that can tell a replica about a key
 * another replica expired, since that is what an application's session-expired event is
 * made of. {@code redis-adapter-for-spring-session-server-etcd} is that server.
 */
@SpringBootApplication
public class InMemoryRedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(InMemoryRedisAdapterServerApplication.class, args);
	}

}
