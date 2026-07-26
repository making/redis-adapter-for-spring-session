package am.ik.redis.adapter.boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.LongStream;

/**
 * Runs one case many times and reports how long each run took.
 *
 * <p>
 * A case is split into what is prepared and what is timed, because most of these
 * operations need something to exist first: timing a {@code delete} together with the
 * write that made the key deletable would measure the write. {@link Case#prepare} does
 * whatever the iteration needs and hands back the one call that is measured.
 *
 * <p>
 * The concurrent form drives every run from a virtual thread of its own, which is what
 * the adapter itself does — one per connection — so the contention that shows up here is
 * the contention a deployment has. It reports what failed rather than throwing: a case
 * that is meant to find where retrying stops keeping up has to be able to report that it
 * did.
 */
public final class Benchmark {

	private Benchmark() {
	}

	/**
	 * What is measured, once per iteration.
	 */
	public interface Case {

		/**
		 * Prepares iteration {@code iteration} and returns the call to be timed.
		 * Everything this method does is outside the measurement.
		 * @param iteration the iteration number, starting at zero, unique within a thread
		 * @return the operation to time
		 */
		Runnable prepare(int iteration);

	}

	/**
	 * Measures a case one run at a time.
	 * @param name what is being measured
	 * @param warmup how many runs to throw away first, so that class loading and the JIT
	 * are not what is reported
	 * @param iterations how many runs to measure
	 * @param operation the case
	 * @return the measurement
	 */
	public static Measurement measure(String name, int warmup, int iterations, Case operation) {
		for (int i = 0; i < warmup; i++) {
			operation.prepare(-1 - i).run();
		}
		long[] nanos = new long[iterations];
		for (int i = 0; i < iterations; i++) {
			Runnable timed = operation.prepare(i);
			long began = System.nanoTime();
			timed.run();
			nanos[i] = System.nanoTime() - began;
		}
		// Throughput is what one caller gets by repeating the operation, so it comes from
		// the time spent in the operation rather than from the wall clock: what an
		// iteration
		// had to set up first is not part of it.
		return Measurement.of(name, nanos, LongStream.of(nanos).sum(), "");
	}

	/**
	 * Measures a case run by many threads at once.
	 * @param name what is being measured
	 * @param threads how many virtual threads run it
	 * @param perThread how many runs each of them makes
	 * @param caseForThread builds the case for one thread, which is where a thread takes
	 * keys of its own
	 * @return the measurement, whose note names what failed if anything did
	 */
	public static Measurement measureConcurrently(String name, int threads, int perThread,
			IntFunction<Case> caseForThread) {
		ConcurrentLinkedQueue<Long> nanos = new ConcurrentLinkedQueue<>();
		List<String> failures = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		AtomicLong wall = new AtomicLong();
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			Case work = caseForThread.apply(t);
			workers.add(Thread.ofVirtual().name(name + "-" + t).unstarted(() -> {
				ready.countDown();
				try {
					go.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
				for (int i = 0; i < perThread; i++) {
					try {
						Runnable timed = work.prepare(i);
						long began = System.nanoTime();
						timed.run();
						nanos.add(System.nanoTime() - began);
					}
					catch (RuntimeException ex) {
						failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
					}
				}
			}));
		}
		workers.forEach(Thread::start);
		try {
			ready.await();
			long start = System.nanoTime();
			go.countDown();
			for (Thread worker : workers) {
				worker.join();
			}
			wall.set(System.nanoTime() - start);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while measuring " + name, ex);
		}
		long[] samples = nanos.stream().mapToLong(Long::longValue).toArray();
		String note = failures.isEmpty() ? ""
				: failures.size() + " of " + (threads * perThread) + " failed: " + failures.getFirst();
		return Measurement.of(name, samples, wall.get(), note);
	}

}
