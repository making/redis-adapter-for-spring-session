package am.ik.redis.adapter;

import java.util.List;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test proving the core module compiles, the layered package skeleton exists and
 * every package is null-marked, and the JUnit 5 + AssertJ test infrastructure is wired.
 */
class CoreModuleSmokeTest {

	@Test
	void everyCorePackageExistsAndIsNullMarked() throws ClassNotFoundException {
		List<String> packages = List.of("am.ik.redis.adapter", "am.ik.redis.adapter.store",
				"am.ik.redis.adapter.protocol", "am.ik.redis.adapter.command", "am.ik.redis.adapter.pubsub",
				"am.ik.redis.adapter.server");
		for (String name : packages) {
			Class<?> packageInfo = Class.forName(name + ".package-info");
			assertThat(packageInfo.getPackage().isAnnotationPresent(NullMarked.class))
				.as("package %s is null-marked", name)
				.isTrue();
		}
	}

}
