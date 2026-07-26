package am.ik.redis.adapter.boot.dynamodb;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import am.ik.redis.adapter.boot.CallCounter;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Counts what DynamoDB was asked, from the SDK's own execution pipeline.
 *
 * <p>
 * The emulator serves no metrics endpoint, so — unlike the etcd counter, which scrapes
 * the cluster — this one rides the client as an {@code ExecutionInterceptor} and counts
 * every transmission, retries included, by operation name. That is still the number a
 * design is argued about in, and here it is also the bill: every transmission is a billed
 * request, transactional writes at twice the plain rate.
 */
final class DynamoDbCallCounter implements CallCounter, ExecutionInterceptor {

	private final ConcurrentHashMap<String, LongAdder> calls = new ConcurrentHashMap<>();

	@Override
	public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
		String operation = executionAttributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
		this.calls.computeIfAbsent((operation != null) ? operation : "?", name -> new LongAdder()).increment();
	}

	@Override
	public Snapshot begin() {
		Map<String, Long> before = counts();
		return new Snapshot() {
			@Override
			public String summary() {
				Map<String, Long> spent = since(before);
				if (spent.isEmpty()) {
					return "no DynamoDB calls";
				}
				long total = spent.values().stream().mapToLong(Long::longValue).sum();
				return total + " calls: "
						+ spent.entrySet()
							.stream()
							.map(entry -> entry.getKey() + " " + entry.getValue())
							.collect(Collectors.joining(", "));
			}

			@Override
			public String perOperation(int operations) {
				Map<String, Long> spent = since(before);
				if (spent.isEmpty()) {
					return "no DynamoDB calls";
				}
				long total = spent.values().stream().mapToLong(Long::longValue).sum();
				String breakdown = spent.entrySet()
					.stream()
					.map(entry -> entry.getKey() + " " + rounded(entry.getValue() / (double) operations))
					.collect(Collectors.joining(", "));
				return "%s calls/op (%s)".formatted(rounded(total / (double) operations), breakdown);
			}
		};
	}

	private Map<String, Long> counts() {
		Map<String, Long> counts = new TreeMap<>();
		this.calls.forEach((operation, count) -> counts.put(operation, count.sum()));
		return counts;
	}

	private Map<String, Long> since(Map<String, Long> before) {
		Map<String, Long> difference = new TreeMap<>();
		counts().forEach((operation, count) -> {
			long spent = count - before.getOrDefault(operation, 0L);
			if (spent > 0) {
				difference.put(operation, spent);
			}
		});
		return difference.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
	}

	private static String rounded(double value) {
		if (value == Math.rint(value)) {
			return "%.0f".formatted(value);
		}
		return (Math.abs(value) < 1) ? "%.3f".formatted(value) : "%.1f".formatted(value);
	}

}
