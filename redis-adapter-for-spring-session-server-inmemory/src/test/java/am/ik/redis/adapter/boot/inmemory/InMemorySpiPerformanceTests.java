package am.ik.redis.adapter.boot.inmemory;

import am.ik.redis.adapter.boot.BackendSpiBenchmark;
import am.ik.redis.adapter.boot.CallCounter;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * What one operation of this backend costs, measured at the SPI.
 *
 * <p>
 * These are the baseline numbers: the same cases every other backend is measured with,
 * against a store with the network taken out. A backend that is slower than this is
 * slower by the amount its store costs, which is the only way to tell "the store is slow"
 * from "the adapter is slow".
 *
 * <p>
 * It asserts nothing about the numbers. A benchmark that fails when a machine is busy is
 * a benchmark that gets disabled; this one reports, and the conclusions are drawn by
 * whoever ran it. Run it with {@code ./mvnw test -Pperformance}.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InMemorySpiPerformanceTests {

	private static final PerformanceReport report = new PerformanceReport("The SPI, in memory");

	private static InMemoryKeyValueStore store;

	private static BackendSpiBenchmark benchmark;

	@BeforeAll
	static void startBackend() {
		store = InMemoryKeyValueStore.create();
		benchmark = new BackendSpiBenchmark(report, "in-memory", store, CallCounter.NONE);
		report.note(describe());
		report.note("Every case is one call on `KeyValueStore`, the seam a backend plugs into. "
				+ "`in-memory` is `InMemoryKeyValueStore`, which is the adapter's own code with no store "
				+ "underneath it.");
	}

	@AfterAll
	static void closeBackend() {
		store.close();
		report.write("spi-in-memory.md");
	}

	@Test
	@Order(1)
	void oneOperationAtATime() {
		benchmark.oneOperationAtATime();
	}

	@Test
	@Order(2)
	void sessionsOfEverySize() {
		benchmark.sessionsOfEverySize();
	}

	@Test
	@Order(3)
	void addingToABucketThatIsAlreadyBig() {
		benchmark.addingToABucketThatIsAlreadyBig();
	}

	@Test
	@Order(4)
	void everySessionInTheSameMinute() {
		benchmark.everySessionInTheSameMinute();
	}

	/**
	 * Returns what the machine should be described as beside the numbers, since a
	 * baseline is only a baseline for the machine it was taken on.
	 * @return one markdown line naming what took the measurements
	 */
	private static String describe() {
		Runtime runtime = Runtime.getRuntime();
		return "Measured on %s %s, %d available processors, JVM %s.".formatted(System.getProperty("os.name"),
				System.getProperty("os.arch"), runtime.availableProcessors(), Runtime.version());
	}

}
