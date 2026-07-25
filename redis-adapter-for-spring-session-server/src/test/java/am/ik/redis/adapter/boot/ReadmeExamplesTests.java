package am.ik.redis.adapter.boot;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.command.StandardCommands;
import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the README to the code it documents.
 *
 * <p>
 * Every example the README shows is quoted from a file this module compiles and runs, and
 * every table that names something the code declares is checked against the declaration.
 * A README is only worth reading if it is true, and the way it stops being true is that
 * the code moves on without it — which is exactly what these tests fail on.
 *
 * <p>
 * What the examples <em>do</em> is proven elsewhere: the application-side ones by the
 * tests beside them under {@code com.example}, and the settings by
 * {@link ReadmeConfigurationExamplesTests}, which starts a real server and a real client
 * from them.
 */
class ReadmeExamplesTests {

	/** Where each Java example is quoted from, by the name the README marks it with. */
	private static final Map<String, Path> JAVA_EXAMPLES = javaExamples();

	/** Where each settings example is quoted from, by the same name. */
	private static final Map<String, String> SETTINGS_EXAMPLES = settingsExamples();

	private static Map<String, Path> javaExamples() {
		Map<String, Path> examples = new LinkedHashMap<>();
		examples.put("session-event-listener", source("com/example/session/SessionEventListener.java"));
		examples.put("find-by-index-name", source("com/example/session/ActiveUserSessions.java"));
		examples.put("sorted-set-expiration-config", source("com/example/session/SortedSetExpirationConfig.java"));
		examples.put("backend-factory", source("com/example/backend/MyKeyValueStoreFactory.java"));
		examples.put("backend-registration", source("com/example/backend/MyBackendConfiguration.java"));
		return examples;
	}

	private static Map<String, String> settingsExamples() {
		Map<String, String> examples = new LinkedHashMap<>();
		examples.put("app-connection", "readme/application.properties");
		examples.put("app-indexed", "readme/application.properties");
		examples.put("app-namespace", "readme/application.properties");
		examples.put("app-password", "readme/application.properties");
		examples.put("app-tls", "readme/application.properties");
		examples.put("server-settings", "readme/server.properties");
		examples.put("server-in-memory", "readme/server.properties");
		examples.put("server-etcd", "readme/server.properties");
		examples.put("server-tls", "readme/server.properties");
		examples.put("server-env", "readme/server.env");
		examples.put("backend-selection", "readme/server.properties");
		return examples;
	}

	private static Path source(String path) {
		return Path.of("src", "test", "java").resolve(path);
	}

	@Test
	void everyExampleIsQuotedFromTheFileItLivesIn() {
		for (ReadmeSnippets.Block block : named()) {
			String name = String.valueOf(block.name());
			assertThat(block.content()).as("the example marked %s", name).isEqualTo(quoted(name));
		}
	}

	/**
	 * Returns the example as the file it is quoted from writes it.
	 * @param name the name the README marks it with
	 * @return the region of the file
	 */
	private static String quoted(String name) {
		Path source = JAVA_EXAMPLES.get(name);
		if (source != null) {
			return ReadmeSnippets.region(source, name);
		}
		return ReadmeSnippets.regionOfResource(
				Objects.requireNonNull(SETTINGS_EXAMPLES.get(name), () -> "no file holds the example " + name), name);
	}

	/**
	 * An example that is not marked is an example nothing checks, so the README is not
	 * allowed to hold one. Blocks in any other language are prose — a command to type, a
	 * dependency to copy — and are left alone.
	 */
	@Test
	void everyCodeAndSettingsBlockIsMarkedAsAnExample() {
		List<String> unmarked = ReadmeSnippets.blocks()
			.stream()
			.filter(block -> block.name() == null)
			.filter(block -> Set.of("java", "properties").contains(block.language()))
			.map(ReadmeSnippets.Block::content)
			.toList();

		assertThat(unmarked).isEmpty();
	}

	@Test
	void everyExampleThatIsQuotedFromSomewhereIsShown() {
		List<String> shown = named().stream().map(block -> String.valueOf(block.name())).toList();

		assertThat(shown).containsExactlyInAnyOrderElementsOf(
				Stream.concat(JAVA_EXAMPLES.keySet().stream(), SETTINGS_EXAMPLES.keySet().stream()).toList());
	}

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property the server binds and invents none.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyTheServerBinds() {
		List<String> documented = ReadmeSnippets.tableRowHeadings("properties:redis-adapter")
			.stream()
			.flatMap(cell -> ReadmeSnippets.codeSpans(cell).stream())
			.toList();

		List<String> bound = new ArrayList<>();
		bound.addAll(propertyNames("redis-adapter", RedisAdapterProperties.class));
		bound.addAll(propertyNames("redis-adapter.in-memory", InMemoryBackendProperties.class));
		bound.addAll(propertyNames("redis-adapter.etcd", EtcdBackendProperties.class));
		assertThat(documented).containsExactlyInAnyOrderElementsOf(bound);
	}

	@Test
	void theCommandTableNamesOnlyCommandsTheAdapterAnswers() {
		CommandDispatcher dispatcher = StandardCommands.dispatcher();

		List<String> documented = ReadmeSnippets.tableRowHeadings("commands")
			.stream()
			.flatMap(cell -> ReadmeSnippets.codeSpans(cell).stream())
			.toList();

		assertThat(documented).isNotEmpty()
			.allSatisfy(command -> assertThat(dispatcher.supports(command)).as("the README documents %s", command)
				.isTrue());
	}

	@Test
	void theBackendGuideListsEveryMethodOfTheStoreSpi() {
		List<String> documented = ReadmeSnippets.tableRowHeadings("methods:KeyValueStore")
			.stream()
			.flatMap(cell -> ReadmeSnippets.codeSpans(cell).stream())
			.map(span -> span.substring(0, span.indexOf('(')))
			.toList();

		assertThat(documented).containsExactlyInAnyOrderElementsOf(
				Stream.of(KeyValueStore.class.getDeclaredMethods()).map(Method::getName).toList());
	}

	private static List<ReadmeSnippets.Block> named() {
		return ReadmeSnippets.blocks().stream().filter(block -> block.name() != null).toList();
	}

	/**
	 * Returns the names a properties record binds, the nested ones included, the way an
	 * operator writes them.
	 * @param prefix the prefix the record is bound under
	 * @param properties the record to walk
	 * @return the property names
	 */
	private static List<String> propertyNames(String prefix, Class<?> properties) {
		List<String> names = new ArrayList<>();
		for (RecordComponent component : properties.getRecordComponents()) {
			String name = prefix + "." + kebabCase(component.getName());
			if (component.getType().isRecord()) {
				names.addAll(propertyNames(name, component.getType()));
			}
			else {
				names.add(name);
			}
		}
		return names;
	}

	private static String kebabCase(String name) {
		return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
	}

}
