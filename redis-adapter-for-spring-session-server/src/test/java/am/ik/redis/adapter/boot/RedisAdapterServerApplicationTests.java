package am.ik.redis.adapter.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test proving the server module compiles, the application class is a Spring Boot
 * application, and the JUnit 5 + AssertJ test infrastructure from
 * {@code spring-boot-starter-test} is wired. It deliberately does not start an
 * application context, since no beans are defined yet.
 */
class RedisAdapterServerApplicationTests {

	@Test
	void applicationClassIsAnnotatedWithSpringBootApplication() {
		assertThat(RedisAdapterServerApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
	}

}
