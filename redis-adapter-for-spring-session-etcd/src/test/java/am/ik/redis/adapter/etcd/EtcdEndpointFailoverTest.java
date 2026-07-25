package am.ik.redis.adapter.etcd;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cluster has several members, and an adapter is given all of them for a reason: one
 * going away must cost a request rather than the sessions.
 *
 * <p>
 * A member that has stopped serving is a {@link TcpProxy} in front of the real etcd with
 * nothing going through it, which is what a client actually sees in that case — the
 * connection is accepted by something and then goes nowhere. etcd itself stays up, so the
 * assertions are about the client's choice of member and nothing else.
 */
class EtcdEndpointFailoverTest {

	private static final AtomicInteger keyspace = new AtomicInteger();

	@Test
	void aMemberThatIsNotServingIsPassedOver(TestInfo test) {
		try (TcpProxy dead = TcpProxy.to(EtcdCluster.endpoint())) {
			dead.blackhole(true);
			try (EtcdKeyValueStore store = store(List.of(dead.endpoint(), EtcdCluster.endpoint()), prefix(test))) {
				assertThat(store.append(b("k"), b("value"))).isEqualTo(5);
				assertThat(store.exists(b("k"))).isTrue();
			}
		}
	}

	/**
	 * The member that was serving stops mid-life, which is what a rolling restart of a
	 * cluster does. The next request finds the one that is still there.
	 */
	@Test
	void aMemberThatGoesAwayCostsOneRequestRatherThanTheStore(TestInfo test) {
		try (TcpProxy first = TcpProxy.to(EtcdCluster.endpoint())) {
			try (EtcdKeyValueStore store = store(List.of(first.endpoint(), EtcdCluster.endpoint()), prefix(test))) {
				store.append(b("k"), b("before"));

				first.blackhole(true);

				store.append(b("k"), b("-after"));
				assertThat(store.exists(b("k"))).isTrue();
			}
		}
	}

	/**
	 * When no member answers, the failure has to say so and name where it looked. The
	 * command layer turns it into {@code ERR internal error} and logs it, which is the
	 * right answer: the client is told the write did not happen rather than being told it
	 * did.
	 */
	@Test
	void whenNoMemberAnswersTheFailureSaysWhereItLooked(TestInfo test) {
		try (TcpProxy only = TcpProxy.to(EtcdCluster.endpoint())) {
			only.blackhole(true);
			try (EtcdKeyValueStore store = store(List.of(only.endpoint()), prefix(test))) {
				assertThatThrownBy(() -> store.append(b("k"), b("v"))).isInstanceOf(EtcdException.class)
					.hasMessageContaining("No etcd endpoint could be reached")
					.hasMessageContaining(only.endpoint());
			}
		}
	}

	/**
	 * A store built while its whole cluster is unreachable still has to be a store: every
	 * backend is created as the application starts, whether or not it is the selected
	 * one, so an etcd that is away must not stop the server from coming up. It serves
	 * once etcd is back.
	 */
	@Test
	void aStoreBuiltWhileTheClusterIsAwayServesOnceItIsBack(TestInfo test) {
		try (TcpProxy only = TcpProxy.to(EtcdCluster.endpoint())) {
			only.blackhole(true);
			try (EtcdKeyValueStore store = store(List.of(only.endpoint()), prefix(test))) {
				RecordingListener listener = new RecordingListener();
				store.addKeyEventListener(listener);

				only.blackhole(false);

				store.append(b("k"), b("v"));
				store.delete(b("k"));
				listener.awaitEvent("deleted k");
			}
		}
	}

	private static EtcdKeyValueStore store(List<String> endpoints, String prefix) {
		return EtcdKeyValueStore.builder()
			.endpoints(endpoints)
			.keyPrefix(prefix)
			.watchRetryDelay(Duration.ofMillis(100))
			.build();
	}

	private static String prefix(TestInfo test) {
		return "/failover/" + keyspace.incrementAndGet() + "-" + test.getTestMethod().orElseThrow().getName() + "/";
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
