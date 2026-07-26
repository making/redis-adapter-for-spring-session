package am.ik.redis.adapter.boot.foundationdb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the adapter server that keeps its sessions in FoundationDB.
 *
 * <p>
 * One thing has to be true of the machine or image this runs on, and it is the price of
 * FoundationDB having no HTTP API at all: <strong>the native client ({@code libfdb_c})
 * has to be installed</strong>, matching the cluster's version. It is not in any jar.
 * Everything else is ordinary Spring Boot configuration:
 *
 * <pre>{@code
 * java -jar redis-adapter-for-spring-session-server-foundationdb-<version>-exec.jar \
 *     --redis-adapter.foundationdb.cluster-file=/etc/foundationdb/fdb.cluster
 * }</pre>
 *
 * The usual sources apply — a command line argument, an {@code application.properties}
 * beside the jar, or the environment variables a container platform hands over. A
 * platform that delivers configuration rather than files can hand over the cluster file's
 * contents instead of its path, and the server writes one:
 *
 * <pre>{@code
 * REDIS_ADAPTER_PORT=16379
 * REDIS_ADAPTER_PASSWORD=s3cret
 * REDIS_ADAPTER_FOUNDATIONDB_CLUSTER_FILE_CONTENTS=redis:adapter@fdb-0:4500,fdb-1:4500,fdb-2:4500
 * REDIS_ADAPTER_FOUNDATIONDB_KEY_PREFIX=/redis-adapter/
 * }</pre>
 *
 * <p>
 * The application also serves {@code /actuator/health} and the metrics of whatever
 * registry is on its classpath over HTTP, on the usual Spring Boot port — that port
 * carries nothing but the actuator.
 *
 * <h2>Running more than one</h2> This is a server for running several replicas behind a
 * load balancer. The sessions live in FoundationDB, so every replica serves the same ones
 * and they outlive all of them, and a key one replica removes reaches the clients of the
 * others through the key-event log — which is what an application's session-expired event
 * is made of. Expiry is the adapter's own work here, since FoundationDB has no TTL: one
 * replica per database is elected to sweep, and the rest simply listen.
 */
@SpringBootApplication
public class FoundationDbRedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FoundationDbRedisAdapterServerApplication.class, args);
	}

}
