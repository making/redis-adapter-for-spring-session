package am.ik.redis.adapter.boot;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Every example the README shows is quoted from a file that is compiled and run, and
 * every table that names something the code declares is checked against the declaration.
 * A README is only worth reading if it is true, and the way it stops being true is that
 * the code moves on without it — which is exactly what these tests fail on.
 *
 * <p>
 * The README is one document over several modules, so this test comes in two halves. An
 * example is text, so every one of them is compared here against the file it lives in,
 * whichever module that is. A property table names classes, and this module cannot see
 * the servers built on top of it, so each of those checks its own table and what is left
 * here is that no table has been added which nobody checks.
 *
 * <p>
 * What the examples <em>do</em> is proven elsewhere: the application-side ones by the
 * tests beside them under {@code com.example}, and the settings by
 * {@link ReadmeConfigurationExamplesTests} and its counterpart in each server module,
 * which start a real server and a real client from them.
 */
class ReadmeExamplesTests {

	/** Where each example is quoted from, by the name the README marks it with. */
	private static final Map<String, Path> EXAMPLES = examples();

	/**
	 * The property tables the README holds, and the module whose tests check each one
	 * against the record that binds it. A table that appears here against nobody is a
	 * table nobody checks.
	 */
	private static final Map<String, String> PROPERTY_TABLES = Map.of("properties:redis-adapter", "this module",
			"properties:redis-adapter.in-memory", "redis-adapter-for-spring-session-server-inmemory",
			"properties:redis-adapter.etcd", "redis-adapter-for-spring-session-server-etcd");

	private static Map<String, Path> examples() {
		Map<String, Path> examples = new LinkedHashMap<>();
		examples.put("session-event-listener", source("com/example/session/SessionEventListener.java"));
		examples.put("find-by-index-name", source("com/example/session/ActiveUserSessions.java"));
		examples.put("sorted-set-expiration-config", source("com/example/session/SortedSetExpirationConfig.java"));
		examples.put("backend-factory", source("com/example/backend/MyKeyValueStoreFactory.java"));
		examples.put("backend-registration", source("com/example/backend/MyBackendConfiguration.java"));
		examples.put("backend-application", source("com/example/backend/MyRedisAdapterServerApplication.java"));
		examples.put("app-connection", settings("readme/application.properties"));
		examples.put("app-indexed", settings("readme/application.properties"));
		examples.put("app-namespace", settings("readme/application.properties"));
		examples.put("app-password", settings("readme/application.properties"));
		examples.put("app-tls", settings("readme/application.properties"));
		examples.put("server-settings", settings("readme/server.properties"));
		examples.put("server-tls", settings("readme/server.properties"));
		examples.put("server-env", settings("readme/server.env"));
		examples.put("server-in-memory", backend("server-inmemory", "readme/server-in-memory.properties"));
		examples.put("server-etcd", backend("server-etcd", "readme/server-etcd.properties"));
		examples.put("server-etcd-env", backend("server-etcd", "readme/server-etcd.env"));
		return examples;
	}

	private static Path source(String path) {
		return Path.of("src", "test", "java").resolve(path);
	}

	private static Path settings(String resource) {
		return Path.of("src", "test", "resources").resolve(resource);
	}

	/**
	 * Returns where a settings example of a backend's own server module lives. It is read
	 * as a file rather than resolved as a class path resource: an example is text, and a
	 * module that had to depend on the servers built on top of it would be the wrong way
	 * round.
	 * @param module the server module, without the project's artifact prefix
	 * @param resource the file, under that module's test resources
	 * @return the path, from this module's directory
	 */
	private static Path backend(String module, String resource) {
		return Path.of("..", "redis-adapter-for-spring-session-" + module, "src", "test", "resources")
			.resolve(resource);
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
		return ReadmeSnippets
			.region(Objects.requireNonNull(EXAMPLES.get(name), () -> "no file holds the example " + name), name);
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

		assertThat(shown).containsExactlyInAnyOrderElementsOf(EXAMPLES.keySet());
	}

	/**
	 * The configuration reference is the one table an operator reads instead of the code,
	 * so it lists every property the server binds and invents none. This is the server's
	 * own table; a backend's is checked by the server module built around it.
	 */
	@Test
	void theConfigurationReferenceListsEveryPropertyTheServerBinds() {
		assertThat(ReadmeSnippets.documentedProperties("properties:redis-adapter")).containsExactlyInAnyOrderElementsOf(
				ReadmeSnippets.boundProperties("redis-adapter", RedisAdapterProperties.class));
	}

	/**
	 * A property table nobody checks is a property table that goes stale, and no module
	 * can see all of them: this one cannot see the backends, and a backend cannot see the
	 * others. What is left to assert here is that every table in the README has an owner.
	 */
	@Test
	void everyPropertyTableIsCheckedBySomeModule() {
		assertThat(ReadmeSnippets.markers("properties:")).containsExactlyInAnyOrderElementsOf(PROPERTY_TABLES.keySet());
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

}
