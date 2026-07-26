package am.ik.redis.adapter.boot;

import java.util.Arrays;

/**
 * One measured case: what it was, how long each run of it took, and how fast they came
 * out.
 *
 * <p>
 * Percentiles rather than an average, because an average hides the case an operator
 * actually notices: a session save that usually costs a millisecond and occasionally
 * costs fifty is a different thing from one that always costs five, and only the tail
 * says which. The note carries whatever else the case learned — how many round trips the
 * operation made, how many attempts contention cost, what a failure said.
 *
 * @param name what was measured
 * @param samples how many times it ran
 * @param p50Millis the median
 * @param p95Millis the 95th percentile
 * @param p99Millis the 99th percentile
 * @param maxMillis the slowest run
 * @param perSecond how many completed per second of wall clock, which is the only number
 * that means anything when several ran at once
 * @param note what else the case has to say, or an empty string
 */
public record Measurement(String name, int samples, double p50Millis, double p95Millis, double p99Millis,
		double maxMillis, double perSecond, String note) {

	/** The header of the table {@link #markdownRow()} produces a line of. */
	public static final String MARKDOWN_HEADER = """
			| Case | n | p50 | p95 | p99 | max | ops/s | Notes |
			| --- | --- | --- | --- | --- | --- | --- | --- |""";

	/**
	 * Draws a measurement out of the times a case took.
	 * @param name what was measured
	 * @param nanos every run's duration in nanoseconds; not modified
	 * @param wallNanos how long the whole case took, which is what throughput is drawn
	 * from
	 * @param note what else to report, or an empty string
	 * @return the measurement
	 */
	public static Measurement of(String name, long[] nanos, long wallNanos, String note) {
		if (nanos.length == 0) {
			throw new IllegalArgumentException("nothing was measured for " + name);
		}
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		return new Measurement(name, sorted.length, millis(percentile(sorted, 0.50)), millis(percentile(sorted, 0.95)),
				millis(percentile(sorted, 0.99)), millis(sorted[sorted.length - 1]),
				sorted.length / (wallNanos / 1_000_000_000.0), note);
	}

	/**
	 * Returns this measurement with something else to say, which is usually only known
	 * once it has run.
	 * @param note what the case learned
	 * @return a copy carrying that note
	 */
	public Measurement withNote(String note) {
		return new Measurement(this.name, this.samples, this.p50Millis, this.p95Millis, this.p99Millis, this.maxMillis,
				this.perSecond, this.note.isEmpty() ? note : this.note + "; " + note);
	}

	/**
	 * Returns this measurement as a row of the table {@link #MARKDOWN_HEADER} heads.
	 * @return the markdown row
	 */
	public String markdownRow() {
		return "| %s | %d | %s | %s | %s | %s | %s | %s |".formatted(this.name, this.samples, format(this.p50Millis),
				format(this.p95Millis), format(this.p99Millis), format(this.maxMillis), formatRate(this.perSecond),
				this.note.isEmpty() ? "" : this.note);
	}

	private static long percentile(long[] sorted, double quantile) {
		int index = (int) Math.ceil(quantile * sorted.length) - 1;
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}

	private static double millis(long nanos) {
		return nanos / 1_000_000.0;
	}

	private static String format(double millis) {
		return (millis < 10 ? "%.2f".formatted(millis) : "%.1f".formatted(millis)) + " ms";
	}

	private static String formatRate(double perSecond) {
		return perSecond < 100 ? "%.1f".formatted(perSecond) : "%.0f".formatted(perSecond);
	}

}
