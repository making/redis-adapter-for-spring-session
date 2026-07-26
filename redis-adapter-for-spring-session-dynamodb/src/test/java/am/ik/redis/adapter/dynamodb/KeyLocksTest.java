package am.ik.redis.adapter.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The serialization the counts depend on, without DynamoDB: callers of one key take
 * turns, callers of different keys do not wait for each other, and a key nobody is at
 * holds no lock.
 */
class KeyLocksTest {

	@Test
	void callersOfOneKeyTakeTurns() throws Exception {
		KeyLocks locks = new KeyLocks();
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger overlaps = new AtomicInteger();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> callers = new ArrayList<>();
			for (int i = 0; i < 32; i++) {
				callers.add(executor.submit(() -> locks.exclusively(b("one"), () -> {
					if (inside.incrementAndGet() > 1) {
						overlaps.incrementAndGet();
					}
					try {
						Thread.sleep(2);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					inside.decrementAndGet();
					return null;
				})));
			}
			for (Future<?> caller : callers) {
				caller.get();
			}
		}

		assertThat(overlaps).hasValue(0);
	}

	@Test
	void aKeyNobodyIsAtHoldsNoLock() throws Exception {
		KeyLocks locks = new KeyLocks();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> callers = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				byte[] key = b("key-" + i);
				callers.add(executor.submit(() -> locks.exclusively(key, () -> null)));
			}
			for (Future<?> caller : callers) {
				caller.get();
			}
		}

		assertThat(locks.lockedKeys()).isZero();
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
