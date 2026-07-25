package am.ik.redis.adapter.etcd;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to key events while the watch that delivers them is not connected.
 *
 * <p>
 * This is the part of the backend that only runs when something is wrong, and the part
 * where being wrong is expensive: a missed {@code expired} is a session an application
 * never hears has ended, and it is never retried because nothing knows it was missed. The
 * watch therefore resumes from the revision it had seen rather than from whenever it
 * manages to reconnect, and these tests take the connection away — with etcd itself
 * untouched, which is the only way to write to it while a store is blind — to say that it
 * really does.
 *
 * <p>
 * The blind store reaches etcd through a {@link TcpProxy}; a second store, straight to
 * etcd, stands in for the adapter replica that carries on working.
 */
class EtcdWatchReconnectTest {

	private static final AtomicInteger keyspace = new AtomicInteger();

	/**
	 * A removal during the outage is announced when the watch comes back. Nothing retries
	 * a keyspace notification, so a watch that resumed from "now" would lose it for good.
	 */
	@Test
	void aRemovalWhileTheWatchIsDownIsAnnouncedWhenItComesBack(TestInfo test) {
		String prefix = prefix(test);
		try (TcpProxy proxy = TcpProxy.to(EtcdCluster.endpoint());
				EtcdKeyValueStore blinded = store(proxy.endpoint(), prefix);
				EtcdKeyValueStore other = store(EtcdCluster.endpoint(), prefix)) {
			RecordingListener listener = new RecordingListener();
			blinded.addKeyEventListener(listener);
			// Prove the watch is up through the proxy before taking the proxy away.
			other.append(b("first"), b("v"));
			other.delete(b("first"));
			listener.awaitEvent("deleted first");

			proxy.blackhole(true);
			other.append(b("gone"), b("v"));
			other.delete(b("gone"));
			// While the proxy carries nothing the watch cannot possibly have heard.
			listener.assertSilence(Duration.ofMillis(500));

			proxy.blackhole(false);

			listener.awaitEvent("deleted gone");
		}
	}

	/**
	 * The same for an expiry, which is the one that matters most: it is etcd that removes
	 * the key, at a moment nobody chose, and quite possibly while a replica is
	 * reconnecting.
	 */
	@Test
	void anExpiryWhileTheWatchIsDownIsAnnouncedWhenItComesBack(TestInfo test) {
		String prefix = prefix(test);
		try (TcpProxy proxy = TcpProxy.to(EtcdCluster.endpoint());
				EtcdKeyValueStore blinded = store(proxy.endpoint(), prefix);
				EtcdKeyValueStore other = store(EtcdCluster.endpoint(), prefix)) {
			RecordingListener listener = new RecordingListener();
			blinded.addKeyEventListener(listener);
			other.append(b("dying"), new byte[0]);
			other.expireAt(b("dying"), other.currentTimeMillis() + 1_000);

			proxy.blackhole(true);
			// Long enough for etcd's lease to run out and the key to go while nothing is
			// listening; the silence is both the wait and the proof that it went unheard.
			listener.assertSilence(Duration.ofSeconds(4));
			assertThat(other.exists(b("dying"))).isFalse();

			proxy.blackhole(false);

			listener.awaitEvent("expired dying");
		}
	}

	/**
	 * A connection cut mid-stream is the ordinary case — a rolling restart, a load
	 * balancer, a network blip — and it must cost nothing but the reconnect.
	 */
	@Test
	void aWatchWhoseConnectionIsCutCarriesOnAfterwards(TestInfo test) {
		String prefix = prefix(test);
		try (TcpProxy proxy = TcpProxy.to(EtcdCluster.endpoint());
				EtcdKeyValueStore watching = store(proxy.endpoint(), prefix)) {
			RecordingListener listener = new RecordingListener();
			watching.addKeyEventListener(listener);
			watching.append(b("first"), b("v"));
			watching.delete(b("first"));
			listener.awaitEvent("deleted first");

			proxy.cutConnections();

			watching.append(b("second"), b("v"));
			watching.delete(b("second"));

			listener.awaitEvent("deleted second");
		}
	}

	private static EtcdKeyValueStore store(String endpoint, String prefix) {
		return EtcdKeyValueStore.builder()
			.endpoints(List.of(endpoint))
			.keyPrefix(prefix)
			.watchRetryDelay(Duration.ofMillis(100))
			.build();
	}

	private static String prefix(TestInfo test) {
		return "/watch/" + keyspace.incrementAndGet() + "-" + test.getTestMethod().orElseThrow().getName() + "/";
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
