package am.ik.redis.adapter.store;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guard keeping the core module free of concrete backends.
 *
 * <p>
 * {@code am.ik.redis.adapter.store} is the SPI only. Every {@link KeyValueStore}
 * implementation — including the bundled in-memory one — lives in its own module that
 * depends on core and nothing else, so the pluggable seam is exercised from outside
 * rather than trusted (see {@code .docs/design/architecture.md} §6).
 */
@AnalyzeClasses(packages = "am.ik.redis.adapter", importOptions = ImportOption.DoNotIncludeTests.class)
class StoreSpiArchTest {

	@ArchTest
	static final ArchRule coreShipsNoKeyValueStoreImplementation = noClasses().should()
		.implement(KeyValueStore.class)
		.because(
				"a backend belongs in its own module (such as redis-adapter-for-spring-session-inmemory), not in core");

}
