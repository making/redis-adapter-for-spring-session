package am.ik.redis.adapter.boot;

import java.time.Instant;
import java.util.Objects;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;

/**
 * The numbers an application can plan with: what stock Spring Session costs over the
 * adapter, measured through a real client.
 *
 * <p>
 * This is a level above {@link BackendSpiBenchmark} and answers a different question. One
 * session save is not one backend operation — Spring Session writes the session hash, its
 * shadow key, the expirations bucket and the principal index, and sets an expiry on more
 * than one of them — so the only honest way to say what a request costs is to ask the
 * repository an application would use and time that. Everything below it is what a
 * deployment runs: Lettuce over TCP, the RESP codec, the command layer, the backend.
 *
 * <p>
 * The same harness runs against every backend, which is what makes them comparable: the
 * in-memory column is the adapter's own cost, and the difference is what a shared backend
 * is paid for. A backend that can count its own calls is additionally run once between
 * two counts, so the report says how many calls one Spring Session operation makes.
 */
public final class SessionPerformanceHarness {

	private static final int WARMUP = 10;

	private static final int ITERATIONS = 100;

	/** The iteration number the counted run uses, kept clear of the timed ones. */
	private static final int COUNTED = -1_000;

	private final RedisIndexedSessionRepository sessions;

	private final PerformanceReport report;

	private final String backend;

	private final int attributeBytes;

	private final CallCounter counter;

	/**
	 * @param sessions the repository an application would use, over the adapter
	 * @param report where the numbers go
	 * @param backend what to call this backend in the report
	 * @param attributeBytes how big the one session attribute is
	 * @param counter what counts the calls the backend made, or {@link CallCounter#NONE}
	 */
	public SessionPerformanceHarness(RedisIndexedSessionRepository sessions, PerformanceReport report, String backend,
			int attributeBytes, CallCounter counter) {
		this.sessions = sessions;
		this.report = report;
		this.backend = backend;
		this.attributeBytes = attributeBytes;
		this.counter = counter;
	}

	/**
	 * Measures each thing an application does to a session, one at a time.
	 */
	public void oneRequestAtATime() {
		String section = "One session operation at a time — " + this.backend;
		measure(section, "save a new session", i -> {
			RedisSession session = newSession();
			return () -> this.sessions.save(session);
		});
		measure(section, "load a session (findById)", i -> {
			String id = saved().getId();
			return () -> this.sessions.findById(id);
		});
		measure(section, "save an existing session (a request touching it)", i -> {
			RedisSession session = load(saved().getId());
			session.setLastAccessedTime(Instant.now());
			return () -> this.sessions.save(session);
		});
		measure(section, "change the session id", i -> {
			RedisSession session = load(saved().getId());
			return () -> {
				session.changeSessionId();
				this.sessions.save(session);
			};
		});
		measure(section, "delete a session", i -> {
			String id = saved().getId();
			return () -> this.sessions.deleteById(id);
		});
		measure(section, "find a principal's sessions", i -> {
			RedisSession session = newSession();
			session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "alice-" + i);
			this.sessions.save(session);
			return () -> this.sessions.findByPrincipalName("alice-" + i);
		});
	}

	/**
	 * Measures what an application's request actually is — create a session, read it
	 * back, touch it — from many connections at once, which is the number a deployment is
	 * sized by.
	 * @param threads how many virtual threads drive it
	 * @param perThread how many request cycles each of them makes
	 */
	public void manyRequestsAtOnce(int threads, int perThread) {
		String section = "Request cycles at once — " + this.backend;
		CallCounter.Snapshot before = this.counter.begin();
		Measurement measurement = Benchmark.measureConcurrently(
				"create, load and touch a session, from %d connections".formatted(threads), threads, perThread,
				thread -> i -> () -> {
					RedisSession created = newSession();
					this.sessions.save(created);
					RedisSession loaded = load(created.getId());
					loaded.setLastAccessedTime(Instant.now());
					this.sessions.save(loaded);
				});
		this.report.add(section, measurement.withNote(before.perOperation(threads * perThread)));
	}

	private void measure(String section, String name, Benchmark.Case operation) {
		Measurement measurement = Benchmark.measure(name, WARMUP, ITERATIONS, operation);
		this.report.add(section, measurement.withNote(callsFor(operation)));
	}

	/**
	 * Runs one more instance of a case with nothing else going on and reports what the
	 * backend was asked for it. The preparation is outside the two counts, so what is
	 * counted is the operation and not what it needed to exist.
	 * @param operation the case
	 * @return the summary, or an empty string for a backend that counts nothing
	 */
	private String callsFor(Benchmark.Case operation) {
		Runnable timed = operation.prepare(COUNTED);
		CallCounter.Snapshot before = this.counter.begin();
		timed.run();
		return before.summary();
	}

	private RedisSession newSession() {
		RedisSession session = this.sessions.createSession();
		session.setAttribute("user", payload(this.attributeBytes));
		return session;
	}

	private RedisSession saved() {
		RedisSession session = newSession();
		this.sessions.save(session);
		return session;
	}

	private RedisSession load(String id) {
		return Objects.requireNonNull(this.sessions.findById(id), () -> "no session under " + id);
	}

	/**
	 * Returns an attribute of about the given size. It is a String because that is what
	 * an application puts in a session and what the default serializer then has to carry.
	 * @param bytes roughly how many bytes it should serialize to
	 * @return the attribute
	 */
	private static String payload(int bytes) {
		StringBuilder payload = new StringBuilder(bytes);
		for (int i = 0; i < bytes; i++) {
			payload.append((char) ('a' + (i % 26)));
		}
		return payload.toString();
	}

}
