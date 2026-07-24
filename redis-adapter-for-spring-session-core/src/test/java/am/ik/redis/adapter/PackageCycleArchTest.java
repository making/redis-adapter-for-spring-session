package am.ik.redis.adapter;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Architecture guard for the core module.
 *
 * <p>
 * Enforces the design requirement that there are no circular references between the
 * adapter packages. Each first-level package under {@code am.ik.redis.adapter} (that is,
 * {@code store}, {@code protocol}, {@code command}, {@code pubsub} and {@code server}) is
 * treated as a slice, and the build fails if any dependency cycle is introduced between
 * those slices. As later tasks add real classes this test keeps the layering acyclic.
 */
@AnalyzeClasses(packages = "am.ik.redis.adapter", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageCycleArchTest {

	@ArchTest
	static final ArchRule adapterPackagesAreFreeOfCycles = slices().matching("am.ik.redis.adapter.(*)..")
		.should()
		.beFreeOfCycles();

}
