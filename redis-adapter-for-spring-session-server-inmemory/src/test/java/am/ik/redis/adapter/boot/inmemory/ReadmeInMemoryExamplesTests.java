package am.ik.redis.adapter.boot.inmemory;

import java.time.Duration;
import java.util.Map;

import am.ik.redis.adapter.boot.ReadmeSnippets;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the README's account of this backend to what this backend actually binds.
 *
 * <p>
 * The example is loaded from the file it is quoted from and a real context is built from
 * it, because a property name that is subtly wrong binds to nothing and changes nothing,
 * which is precisely the mistake a documented example is supposed to save a reader from.
 * That the README shows this file's region verbatim is checked in the server module,
 * which is where the README's examples are collected.
 */
class ReadmeInMemoryExamplesTests {

	private static final String SETTINGS = "readme/server-in-memory.properties";

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property this backend binds and invents none.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyThisBackendBinds() {
		assertThat(ReadmeSnippets.documentedProperties("properties:redis-adapter.in-memory"))
			.containsExactlyInAnyOrderElementsOf(
					ReadmeSnippets.boundProperties("redis-adapter.in-memory", InMemoryBackendProperties.class));
	}

	@Test
	void theExampleConfiguresTheBackendItDocuments() {
		Map<String, String> settings = ReadmeSnippets.settings(SETTINGS, "server-in-memory");

		new ApplicationContextRunner().withUserConfiguration(InMemoryBackendConfiguration.class)
			.withPropertyValues(settings.entrySet()
				.stream()
				.map(setting -> setting.getKey() + "=" + setting.getValue())
				.toArray(String[]::new))
			.run(context -> assertThat(context.getBean(InMemoryBackendProperties.class))
				.isEqualTo(new InMemoryBackendProperties(true, Duration.ofSeconds(1))));
	}

}
