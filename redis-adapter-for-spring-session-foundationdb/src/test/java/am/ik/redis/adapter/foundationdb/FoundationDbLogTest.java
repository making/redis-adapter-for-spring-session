package am.ik.redis.adapter.foundationdb;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key-event log: how a removal on one replica reaches the clients of another.
 *
 * <p>
 * FoundationDB has no range watch, so the etcd backend's design — a watch that reports
 * every removal itself — cannot be built. What is built instead is an append-only log
 * keyed by the cluster's own commit versionstamp, read forward from a cursor and woken by
 * a watch on a counter. Three things about it are worth a test of their own, because each
 * is a way the log can be wrong rather than merely slow: the order, the exactly-once, and
 * what a store does when the log was trimmed past where it had read.
 */
class FoundationDbLogTest {

	/**
	 * Commit order is the log's whole claim, and it is not a claim about a clock: a
	 * versionstamp is the cluster's own commit version, so two replicas removing keys at
	 * once still produce one order that everybody reads the same way.
	 */
	@Test
	void everyRemovalReachesTheOtherReplicaOnceAndInCommitOrder() {
		String prefix = "/log-" + UUID.randomUUID() + "/";
		RecordingListener heard = new RecordingListener();
		try (FoundationDbKeyValueStore reader = open(prefix, false);
				FoundationDbKeyValueStore writer = open(prefix, false)) {
			reader.addKeyEventListener(heard);
			List<String> expected = new ArrayList<>();
			for (int removal = 0; removal < 25; removal++) {
				byte[] key = b(prefix + "k" + removal);
				writer.hset(key, Map.of(b("a"), b("1")));
				assertThat(writer.delete(key)).isTrue();
				expected.add("deleted " + prefix + "k" + removal);
			}

			List<String> seen = new ArrayList<>();
			for (int event = 0; event < expected.size(); event++) {
				seen.add(heard.await());
			}

			assertThat(seen).containsExactlyElementsOf(expected);
			// Nothing arrives twice: the cursor only ever moves forward, and a drain that
			// read an entry does not read it again.
			heard.assertSilence(Duration.ofMillis(500));
		}
	}

	/**
	 * A restarted adapter must not replay the log. Its cursor starts at the end, read
	 * before the follower thread starts — which is also what keeps a removal in between
	 * from being missed.
	 */
	@Test
	void aStoreThatStartsOnANonEmptyLogDoesNotReplayIt() {
		String prefix = "/log-restart-" + UUID.randomUUID() + "/";
		try (FoundationDbKeyValueStore writer = open(prefix, false)) {
			for (int removal = 0; removal < 5; removal++) {
				byte[] key = b(prefix + "old" + removal);
				writer.hset(key, Map.of(b("a"), b("1")));
				writer.delete(key);
			}

			RecordingListener heard = new RecordingListener();
			try (FoundationDbKeyValueStore restarted = open(prefix, false)) {
				restarted.addKeyEventListener(heard);
				heard.assertSilence(Duration.ofMillis(500));

				byte[] fresh = b(prefix + "fresh");
				writer.hset(fresh, Map.of(b("a"), b("1")));
				writer.delete(fresh);

				assertThat(heard.await()).isEqualTo("deleted " + prefix + "fresh");
			}
		}
	}

	/**
	 * What the etcd backend gets from etcd's compaction message, this one has to work out
	 * for itself: the log is trimmed, and a store whose cursor is behind the trim can
	 * neither read what went nor start again from the beginning. It starts from where the
	 * log now does — the watermark the trim left — which is what turns lost events into a
	 * bounded gap rather than a replay of everything or a store that never catches up.
	 */
	@Test
	void aStoreThatArrivesAfterATrimStartsWhereTheLogNowDoesRatherThanReplayingIt() {
		String prefix = "/log-trim-" + UUID.randomUUID() + "/";
		try (FoundationDbKeyValueStore writer = open(prefix, false)) {
			for (int removal = 0; removal < 5; removal++) {
				byte[] key = b(prefix + "trimmed" + removal);
				writer.hset(key, Map.of(b("a"), b("1")));
				writer.delete(key);
			}

			// A retention of one millisecond makes everything already written older than
			// the log keeps, so the next sweep trims all of it and records how far it
			// got.
			try (FoundationDbKeyValueStore trimmer = FoundationDbKeyValueStore.builder()
				.clusterFile(FdbCluster.clusterFile())
				.keyPrefix(prefix)
				.sweepInterval(Duration.ofMillis(100))
				.logRetention(Duration.ofMillis(1))
				.build()) {
				RecordingListener heard = new RecordingListener();
				try (FoundationDbKeyValueStore late = open(prefix, false)) {
					late.addKeyEventListener(heard);
					// Nothing that was trimmed is replayed, however long we listen.
					heard.assertSilence(Duration.ofSeconds(1));

					byte[] fresh = b(prefix + "after-the-trim");
					writer.hset(fresh, Map.of(b("a"), b("1")));
					writer.delete(fresh);

					assertThat(heard.await()).isEqualTo("deleted " + prefix + "after-the-trim");
				}
			}
		}
	}

	private static FoundationDbKeyValueStore open(String prefix, boolean sweeping) {
		return FoundationDbKeyValueStore.builder()
			.clusterFile(FdbCluster.clusterFile())
			.keyPrefix(prefix)
			.sweeperEnabled(sweeping)
			.sweepInterval(Duration.ofMillis(200))
			.build();
	}

	private static byte[] b(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

}
