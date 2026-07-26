package am.ik.redis.adapter.boot.etcd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What etcd says it did, read off its own {@code /metrics} endpoint.
 *
 * <p>
 * This is how the round-trip count per operation is measured rather than counted by
 * reading the code: etcd reports every gRPC call it handled by method, so the difference
 * across one {@code HSET} is exactly the calls that {@code HSET} made — including the
 * ones a retry added, which is the number worth knowing about a contended key. Nothing
 * has to be added to the adapter to get it, and nothing sits between the store and etcd
 * adding latency to what is being timed.
 *
 * <p>
 * It also carries etcd's own disk histograms, which are the floor everything else is
 * measured above: a write cannot be faster than the fsync that commits it.
 *
 * @param samples every counter and histogram bucket, keyed by its name and labels exactly
 * as etcd printed it
 */
record EtcdMetrics(Map<String, Double> samples) {

	private static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private static final String CALLS = "grpc_server_handled_total";

	/**
	 * Reads the metrics of the etcd at {@code endpoint}.
	 * @param endpoint the client URL
	 * @return what it reported
	 */
	static EtcdMetrics scrape(String endpoint) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/metrics")).GET().build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("etcd answered " + response.statusCode() + " for /metrics");
			}
			Map<String, Double> samples = new LinkedHashMap<>();
			response.body().lines().filter(line -> !line.startsWith("#")).forEach(line -> {
				int space = line.lastIndexOf(' ');
				if (space <= 0) {
					return;
				}
				try {
					samples.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
				}
				catch (NumberFormatException ex) {
					// A non-numeric sample is of no use here (etcd prints a few NaNs).
				}
			});
			return new EtcdMetrics(samples);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read the metrics of " + endpoint, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while reading the metrics of " + endpoint, ex);
		}
	}

	/**
	 * Returns what happened between {@code earlier} and this scrape.
	 * @param earlier the scrape taken before the operation
	 * @return the difference, counter by counter
	 */
	EtcdMetrics since(EtcdMetrics earlier) {
		Map<String, Double> difference = new LinkedHashMap<>();
		this.samples.forEach((name, value) -> difference.put(name, value - earlier.samples.getOrDefault(name, 0.0)));
		return new EtcdMetrics(difference);
	}

	/**
	 * Returns how many gRPC calls each etcd method served, ignoring the ones it did not.
	 * @return the call count by method name, largest first
	 */
	Map<String, Long> callsByMethod() {
		Map<String, Long> byMethod = new TreeMap<>();
		this.samples.forEach((name, value) -> {
			if (!name.startsWith(CALLS + "{") || value < 0.5) {
				return;
			}
			byMethod.merge(label(name, "grpc_method"), Math.round(value), Long::sum);
		});
		return byMethod.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
	}

	/**
	 * Returns how many gRPC calls etcd served in all.
	 * @return the total call count
	 */
	long calls() {
		return callsByMethod().values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * Returns the calls per operation, which is the number the design is judged on: how
	 * many raft operations one Redis command costs.
	 * @param operations how many operations were run while these calls were made
	 * @return a summary such as {@code 2.0 calls/op (Range 500, Txn 500)}
	 */
	String callsPerOperation(int operations) {
		Map<String, Long> byMethod = callsByMethod();
		if (byMethod.isEmpty()) {
			return "no etcd calls";
		}
		String breakdown = byMethod.entrySet()
			.stream()
			.map(entry -> entry.getKey() + " " + rounded(entry.getValue() / (double) operations))
			.collect(Collectors.joining(", "));
		return "%s calls/op (%s)".formatted(rounded(calls() / (double) operations), breakdown);
	}

	/**
	 * Returns the calls of a single operation, named.
	 * @return a summary such as {@code 3 calls: Range 1, Txn 1, LeaseGrant 1}
	 */
	String callSummary() {
		Map<String, Long> byMethod = callsByMethod();
		if (byMethod.isEmpty()) {
			return "no etcd calls";
		}
		return calls() + " calls: "
				+ byMethod.entrySet()
					.stream()
					.map(entry -> entry.getKey() + " " + entry.getValue())
					.collect(Collectors.joining(", "));
	}

	/**
	 * Returns the average of a histogram in milliseconds, drawn from its {@code _sum} and
	 * {@code _count}.
	 * @param histogram the metric name, without a suffix
	 * @return the average in milliseconds, or {@code 0} if nothing was recorded
	 */
	double averageMillis(String histogram) {
		double count = this.samples.getOrDefault(histogram + "_count", 0.0);
		return count == 0 ? 0 : this.samples.getOrDefault(histogram + "_sum", 0.0) / count * 1000;
	}

	private static String label(String sample, String label) {
		int at = sample.indexOf(label + "=\"");
		if (at < 0) {
			return "?";
		}
		int from = at + label.length() + 2;
		return sample.substring(from, sample.indexOf('"', from));
	}

	/**
	 * Formats a call count, keeping enough digits for it to still say something below
	 * one. A batched write costs a small fraction of a call, and "0.0 calls/op" would
	 * report that as none at all.
	 * @param value the count
	 * @return the count as text
	 */
	private static String rounded(double value) {
		if (value == Math.rint(value)) {
			return "%.0f".formatted(value);
		}
		return (Math.abs(value) < 1) ? "%.3f".formatted(value) : "%.1f".formatted(value);
	}

}
