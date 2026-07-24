package am.ik.redis.adapter.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Spring Boot server that runs the Redis adapter for Spring Session.
 *
 * <p>
 * At this stage the application only bootstraps an empty context. The server lifecycle,
 * configuration properties and actuator wiring that start the {@code RedisAdapterServer}
 * from the core module are added in a later task.
 */
@SpringBootApplication
public class RedisAdapterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedisAdapterServerApplication.class, args);
	}

}
