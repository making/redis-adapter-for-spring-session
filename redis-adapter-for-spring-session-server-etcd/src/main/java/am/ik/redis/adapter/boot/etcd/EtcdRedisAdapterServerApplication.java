package am.ik.redis.adapter.boot.etcd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the adapter server that keeps its sessions in etcd.
 *
 * <p>
 * The one setting a deployment has to give is where etcd is:
 *
 * <pre>{@code
 * java -jar redis-adapter-for-spring-session-server-etcd-<version>-exec.jar \
 *     --redis-adapter.etcd.endpoints=http://etcd-0:2379,http://etcd-1:2379
 * }</pre>
 *
 * Everything is configured through
 * {@link am.ik.redis.adapter.boot.RedisAdapterProperties} and
 * {@link EtcdBackendProperties}, so the usual Spring Boot sources apply — a command line
 * argument, an {@code application.properties} beside the jar, or the environment
 * variables a container platform hands over:
 *
 * <pre>{@code
 * REDIS_ADAPTER_PORT=16379
 * REDIS_ADAPTER_PASSWORD=s3cret
 * REDIS_ADAPTER_ETCD_ENDPOINTS=http://etcd-0:2379,http://etcd-1:2379
 * }</pre>
 *
 * <p>
 * The application also serves {@code /actuator/health} and the metrics of whatever
 * registry is on its classpath over HTTP, on the usual Spring Boot port — that port
 * carries nothing but the actuator.
 *
 * <h2>Running more than one</h2> This is the server for running several replicas behind a
 * load balancer. The sessions live in etcd, so every replica serves the same ones and
 * they outlive all of them, and a key one replica expires reaches the clients of the
 * others through etcd's watch — which is what an application's session-expired event is
 * made of.
 */
@SpringBootApplication
public class EtcdRedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(EtcdRedisAdapterServerApplication.class, args);
	}

}
