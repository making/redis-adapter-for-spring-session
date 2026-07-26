package am.ik.redis.adapter.boot.foundationdb;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.redis.adapter.boot.CallCounter;
import am.ik.redis.adapter.foundationdb.FdbCluster;

/**
 * Counts what FoundationDB was made to do, from the cluster's own status document.
 *
 * <p>
 * Read from the cluster rather than from the code, because what the code appears to issue
 * and what a cluster actually commits are two different numbers, and the second one is
 * what a session write costs. <strong>Commits</strong> is the number this backend's
 * design is argued in: the claim is that a whole session save is one, and what that is
 * worth against the other shared backends is {@code .docs/design/architecture.md} §13.8,
 * which owns the comparison because it is the document that also holds theirs.
 *
 * <p>
 * The one awkward thing about the source, and the reason this class is not three lines:
 * <strong>the status document is a periodic snapshot, not a live reading</strong>. Its
 * counters are cumulative and eventually exact, but they lag several seconds behind, so a
 * count taken the moment an operation returns sees the sample from before it and reports
 * zero. Every reading here therefore waits for the snapshot to stop moving before it is
 * used — which is what makes a count correct, and what makes it take seconds. That is
 * affordable in a harness that already runs for minutes and is not run by an ordinary
 * build.
 *
 * <p>
 * The counters are pulled out of the JSON by pattern rather than by parsing it, because
 * this module has no JSON library and is not about to gain one for a test. Each is a flat
 * object of {@code counter}, {@code hz} and {@code roughness}, so the shape being matched
 * is small and stable — and if FoundationDB ever changes it, the report says
 * {@code (no counters)} rather than quietly reporting zero.
 */
record FoundationDbCallCounter() implements CallCounter {

	/** How long between readings while waiting for the snapshot to catch up. */
	private static final Duration SETTLE_POLL = Duration.ofSeconds(1);

	/** How many identical readings in a row mean the snapshot has caught up. */
	private static final int QUIET_READINGS = 2;

	/**
	 * The cluster workload counters worth reporting, by the name they are printed under.
	 * {@code conflicted} is here because it is what §13.1's claim turns on: with one key
	 * per member, replicas adding to one expirations bucket are meant not to conflict at
	 * all.
	 */
	private static final Map<String, String> COUNTERS = new LinkedHashMap<>(
			Map.of("committed", "commits", "conflicted", "conflicts", "reads", "reads", "writes", "writes"));

	@Override
	public Snapshot begin() {
		Map<String, Long> before = settled();
		return new Snapshot() {
			@Override
			public String summary() {
				return describe(spent(), 1, "");
			}

			@Override
			public String perOperation(int operations) {
				return describe(spent(), Math.max(operations, 1), " per operation");
			}

			private Map<String, Long> spent() {
				Map<String, Long> since = new LinkedHashMap<>();
				for (Map.Entry<String, Long> counter : settled().entrySet()) {
					since.put(counter.getKey(), counter.getValue() - before.getOrDefault(counter.getKey(), 0L));
				}
				return since;
			}
		};
	}

	private static String describe(Map<String, Long> spent, int operations, String suffix) {
		if (spent.isEmpty()) {
			return "(no counters)";
		}
		if (spent.values().stream().allMatch(counted -> counted == 0)) {
			// Something was done, so a row of zeros is the snapshot having moved neither
			// side of it rather than an operation that cost nothing — and "cost nothing"
			// is the most misleading thing a counter can say.
			return "(the sampling window caught nothing)";
		}
		StringBuilder said = new StringBuilder();
		for (Map.Entry<String, Long> counter : spent.entrySet()) {
			if (!said.isEmpty()) {
				said.append(", ");
			}
			said.append("%.3f %s".formatted(counter.getValue() / (double) operations, counter.getKey()));
		}
		return said + suffix;
	}

	/**
	 * Returns the workload counters once the cluster's snapshot has stopped moving, which
	 * is when everything that happened before this call is in them.
	 * @return the counters, in the order this class reports them, or empty if the status
	 * document did not hold them
	 */
	private static Map<String, Long> settled() {
		Map<String, Long> previous = read();
		for (int quiet = 0; quiet < QUIET_READINGS;) {
			try {
				Thread.sleep(SETTLE_POLL);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return previous;
			}
			Map<String, Long> now = read();
			quiet = now.equals(previous) ? quiet + 1 : 0;
			previous = now;
		}
		return previous;
	}

	private static Map<String, Long> read() {
		String workload = workload(FdbCluster.statusJson());
		Map<String, Long> counters = new LinkedHashMap<>();
		for (Map.Entry<String, String> wanted : COUNTERS.entrySet()) {
			Matcher found = Pattern
				.compile("\"" + wanted.getKey() + "\"\\s*:\\s*\\{[^}]*?\"counter\"\\s*:\\s*(\\d+)", Pattern.DOTALL)
				.matcher(workload);
			if (found.find()) {
				counters.put(wanted.getValue(), Long.parseLong(found.group(1)));
			}
		}
		return counters;
	}

	/**
	 * Returns the cluster's workload object out of the status document.
	 *
	 * <p>
	 * Narrowing to it is not tidiness: {@code reads} and {@code writes} also name
	 * counters on every process's roles, several of which stay at zero for the whole run,
	 * and a search over the whole document finds one of those first. That reads as "this
	 * operation cost nothing", which is the most misleading answer a counter can give.
	 * @param status the whole status document
	 * @return the workload object, braces balanced, or the whole document if it holds no
	 * such object
	 */
	private static String workload(String status) {
		Matcher start = Pattern.compile("\"workload\"\\s*:\\s*\\{").matcher(status);
		if (!start.find()) {
			return status;
		}
		int depth = 0;
		for (int at = start.end() - 1; at < status.length(); at++) {
			char character = status.charAt(at);
			if (character == '{') {
				depth++;
			}
			else if (character == '}' && --depth == 0) {
				return status.substring(start.end(), at);
			}
		}
		return status;
	}

}
