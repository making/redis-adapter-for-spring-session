package am.ik.redis.adapter.etcd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ValueTooLargeException;
import am.ik.redis.adapter.store.ZSetValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * The {@code KeyValueStore} contract, against a real etcd.
 *
 * <p>
 * Every test runs under a key prefix of its own, so the tests are independent while
 * sharing one container. Expiry is exercised with real deadlines rather than a moved
 * clock wherever etcd's leases are part of what is being tested — the point of this
 * backend is that etcd removes an abandoned key, and only etcd can prove that.
 */
class EtcdKeyValueStoreTest {

	private static final AtomicInteger keyspace = new AtomicInteger();

	private EtcdKeyValueStore store;

	/** The same etcd, read directly, for what the SPI does not expose: leases. */
	private EtcdClient etcd;

	private RecordingListener listener;

	@BeforeEach
	void setUp(TestInfo test) {
		this.store = store(prefix(test));
		this.etcd = EtcdClient.builder().endpoints(List.of(EtcdCluster.endpoint())).build();
		this.listener = new RecordingListener();
		this.store.addKeyEventListener(this.listener);
	}

	@AfterEach
	void tearDown() {
		this.etcd.close();
		this.store.close();
	}

	private static String prefix(TestInfo test) {
		return "/test/" + keyspace.incrementAndGet() + "-" + test.getTestMethod().orElseThrow().getName() + "/";
	}

	private static EtcdKeyValueStore store(String prefix) {
		return EtcdKeyValueStore.builder()
			.endpoints(List.of(EtcdCluster.endpoint()))
			.keyPrefix(prefix)
			.watchRetryDelay(Duration.ofMillis(100))
			.build();
	}

	// --- STRING ------------------------------------------------------------------------

	@Test
	void appendCreatesZeroLengthStringOnAbsentKey() {
		int length = this.store.append(b("k"), new byte[0]);

		assertThat(length).isZero();
		assertThat(this.store.exists(b("k"))).isTrue();
		assertThat(asString(this.store.get(b("k"))).value()).isEmpty();
	}

	@Test
	void appendGrowsExistingString() {
		assertThat(this.store.append(b("k"), b("ab"))).isEqualTo(2);
		assertThat(this.store.append(b("k"), b("cd"))).isEqualTo(4);

		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("abcd"));
	}

	@Test
	void appendAgainstWrongTypeThrows() {
		this.store.hset(b("h"), Map.of(b("f"), b("v")));

		assertThatThrownBy(() -> this.store.append(b("h"), b("x"))).isInstanceOf(TypeMismatchException.class);
	}

	// --- HASH --------------------------------------------------------------------------

	@Test
	void hsetCreatesAndCountsOnlyNewFields() {
		assertThat(this.store.hset(b("h"), fields("a", "1", "b", "2"))).isEqualTo(2);
		assertThat(this.store.hset(b("h"), fields("b", "3", "c", "4"))).isEqualTo(1);

		assertThat(asHash(this.store.get(b("h"))).fields()).hasSize(3)
			.contains(entry(key("a"), b("1")), entry(key("b"), b("3")), entry(key("c"), b("4")));
	}

	@Test
	void hsetKeepsTheFieldOrderItWasGiven() {
		this.store.hset(b("h"), fields("z", "1", "a", "2", "m", "3"));

		assertThat(asHash(this.store.get(b("h"))).fields().keySet()).containsExactly(key("z"), key("a"), key("m"));
	}

	@Test
	void hsetAgainstWrongTypeThrows() {
		this.store.append(b("s"), b("v"));

		assertThatThrownBy(() -> this.store.hset(b("s"), fields("a", "1"))).isInstanceOf(TypeMismatchException.class);
	}

	/**
	 * A cluster has a ceiling on the size of one request — etcd's own
	 * {@code --max-request-bytes}, 1.5 MiB by default — and a value over it is the one
	 * failure here that is about the value rather than about the store: nothing is
	 * unreachable and no retry can make it land. It is therefore raised as the SPI's
	 * exception for exactly that, so the command layer can tell the caller what to do
	 * about it instead of reporting a fault of the adapter's.
	 */
	@Test
	void aValueTooBigForTheClusterIsRefusedAsSuchAndWritesNothing() {
		byte[] oversized = new byte[1_600 * 1024];

		assertThatThrownBy(() -> this.store.hset(b("big"), Map.of(b("sattr:blob"), oversized)))
			.isInstanceOf(ValueTooLargeException.class)
			.hasMessageContaining("etcd refused");
		assertThat(this.store.exists(b("big"))).isFalse();
	}

	/**
	 * The other ceiling, which a deployment meets when it is the lower of the two: the
	 * gateway refuses a message over the size its own gRPC client accepts (2 MiB) before
	 * etcd ever sees it, and says so in words of its own. Both are the same answer to the
	 * caller.
	 */
	@Test
	void aValueOverTheGatewaysOwnMessageLimitIsRefusedAsTooLargeToo() {
		byte[] oversized = new byte[4_000 * 1024];

		assertThatThrownBy(() -> this.store.hset(b("huge"), Map.of(b("sattr:blob"), oversized)))
			.isInstanceOf(ValueTooLargeException.class);
	}

	// --- SET ---------------------------------------------------------------------------

	@Test
	void saddIgnoresMembersItAlreadyHas() {
		assertThat(this.store.sadd(b("s"), List.of(b("a"), b("b")))).isEqualTo(2);
		assertThat(this.store.sadd(b("s"), List.of(b("b"), b("c")))).isEqualTo(1);

		assertThat(asSet(this.store.get(b("s"))).members()).containsExactlyInAnyOrder(key("a"), key("b"), key("c"));
	}

	@Test
	void sremRemovesMembersAndReportsHowMany() {
		this.store.sadd(b("s"), List.of(b("a"), b("b"), b("c")));

		assertThat(this.store.srem(b("s"), List.of(b("a"), b("zzz")))).isEqualTo(1);
		assertThat(asSet(this.store.get(b("s"))).members()).containsExactlyInAnyOrder(key("b"), key("c"));
	}

	/**
	 * Redis removes a set that has lost its last member, and does not announce it:
	 * nothing was deleted, the set merely stopped existing. Spring Session's principal
	 * index empties exactly this way when a user's last session goes, and an application
	 * must not be told that something of its own was deleted.
	 */
	@Test
	void sremRemovesTheKeyWhenTheSetIsEmptiedWithoutAnnouncingIt() {
		this.store.sadd(b("s"), List.of(b("a")));

		assertThat(this.store.srem(b("s"), List.of(b("a")))).isEqualTo(1);

		assertThat(this.store.exists(b("s"))).isFalse();
		assertThat(this.store.get(b("s"))).isNull();
		this.listener.assertSilence(Duration.ofMillis(500));
	}

	@Test
	void sremOnAnAbsentKeyRemovesNothing() {
		assertThat(this.store.srem(b("s"), List.of(b("a")))).isZero();
	}

	@Test
	void saddAfterTheSetWasEmptiedCreatesItAgain() {
		this.store.sadd(b("s"), List.of(b("a")));
		this.store.srem(b("s"), List.of(b("a")));

		assertThat(this.store.sadd(b("s"), List.of(b("b")))).isEqualTo(1);
		assertThat(asSet(this.store.get(b("s"))).members()).containsExactly(key("b"));
	}

	// --- SORTED SET --------------------------------------------------------------------

	@Test
	void zaddAddsAndMovesMembers() {
		assertThat(this.store.zadd(b("z"), scores("a", 1.0, "b", 2.0))).isEqualTo(2);
		assertThat(this.store.zadd(b("z"), scores("b", 9.5))).isZero();

		assertThat(asZSet(this.store.get(b("z"))).scores()).containsOnly(entry(key("a"), 1.0), entry(key("b"), 9.5));
	}

	@Test
	void zremRemovesMembersAndTheEmptiedKeyWithoutAnnouncingIt() {
		this.store.zadd(b("z"), scores("a", 1.0, "b", 2.0));

		assertThat(this.store.zrem(b("z"), List.of(b("a")))).isEqualTo(1);
		assertThat(this.store.zrem(b("z"), List.of(b("b")))).isEqualTo(1);

		assertThat(this.store.exists(b("z"))).isFalse();
		this.listener.assertSilence(Duration.ofMillis(500));
	}

	// --- DELETE ------------------------------------------------------------------------

	@Test
	void deleteRemovesALiveKeyAndAnnouncesIt() {
		this.store.append(b("k"), b("v"));

		assertThat(this.store.delete(b("k"))).isTrue();

		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.listener.await()).isEqualTo("deleted k");
	}

	@Test
	void deleteOfAnAbsentKeyDoesNothing() {
		assertThat(this.store.delete(b("k"))).isFalse();
		this.listener.assertSilence(Duration.ofMillis(500));
	}

	/**
	 * A key whose deadline has passed but which etcd has not collected yet was never
	 * there to be deleted: the answer is {@code false} and the event is an expiry, which
	 * is the difference between Spring Session firing {@code SessionExpiredEvent} and
	 * {@code SessionDeletedEvent}.
	 */
	@Test
	void deleteOfAnOverdueKeyReportsAnExpiryInstead() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.delete(b("k"))).isFalse();

		this.listener.awaitEvent("expired k");
	}

	// --- TTL ---------------------------------------------------------------------------

	@Test
	void expireAtSetsTheDeadlineAndPersistClearsIt() {
		this.store.append(b("k"), b("v"));
		long deadline = this.store.currentTimeMillis() + 60_000;

		assertThat(this.store.expireAt(b("k"), deadline)).isTrue();
		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);

		assertThat(this.store.persist(b("k"))).isTrue();
		assertThat(this.store.getExpireAt(b("k"))).isNull();
		assertThat(this.store.exists(b("k"))).isTrue();
	}

	/**
	 * Pushing the same TTL out again is what Spring Session does to three keys on every
	 * request, and it renews the lease the key is already on rather than moving it onto a
	 * freshly granted one. etcd renews a lease without committing anything to raft, so
	 * this is the difference between one raft write and three; that the lease id has not
	 * changed and that no lease has appeared is what says the renewal happened.
	 */
	@Test
	void expireAtRenewsTheLeaseWhenTheDeadlineNeedsTheSameTtl() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() + 60_000);
		long lease = leaseOf(b("k"));
		Set<Long> granted = leases();
		assertThat(lease).isNotZero();

		long deadline = this.store.currentTimeMillis() + 60_000;
		assertThat(this.store.expireAt(b("k"), deadline)).isTrue();

		assertThat(leaseOf(b("k"))).isEqualTo(lease);
		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);
		// Nothing was granted, so nothing can have been left behind holding nothing
		// either.
		assertThat(leases()).isSubsetOf(granted);
	}

	/**
	 * A deadline that really needs a different lease gets one, and the lease it leaves
	 * behind is revoked rather than left to age out.
	 */
	@Test
	void expireAtGrantsANewLeaseWhenTheTtlChanges() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() + 60_000);
		long lease = leaseOf(b("k"));

		assertThat(this.store.expireAt(b("k"), this.store.currentTimeMillis() + 600_000)).isTrue();

		long replacement = leaseOf(b("k"));
		assertThat(replacement).isNotZero().isNotEqualTo(lease);
		assertThat(leases()).contains(replacement).doesNotContain(lease);
	}

	/**
	 * A key put back on no lease at all is the case a renewal must not be tempted by:
	 * {@code PERSIST} then {@code PEXPIREAT} has to grant.
	 */
	@Test
	void expireAtAfterPersistGrantsALeaseAgain() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() + 60_000);
		this.store.persist(b("k"));
		assertThat(leaseOf(b("k"))).isZero();

		long deadline = this.store.currentTimeMillis() + 60_000;
		assertThat(this.store.expireAt(b("k"), deadline)).isTrue();

		assertThat(leaseOf(b("k"))).isNotZero();
		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);
	}

	@Test
	void expireAtOnAnAbsentKeyReportsFailure() {
		assertThat(this.store.expireAt(b("k"), this.store.currentTimeMillis() + 60_000)).isFalse();
	}

	@Test
	void persistOnAKeyWithNoDeadlineReportsNothingToClear() {
		this.store.append(b("k"), b("v"));

		assertThat(this.store.persist(b("k"))).isFalse();
	}

	@Test
	void aValueIsGoneTheMomentItsDeadlinePassesEvenBeforeEtcdCollectsIt() {
		this.store.append(b("k"), b("v"));

		// etcd leases are whole seconds, so its own collection is still a second or so
		// away
		// when this returns: what makes the key gone now is the deadline in the value.
		this.store.expireAt(b("k"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.get(b("k"))).isNull();
		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.store.getExpireAt(b("k"))).isNull();
		this.listener.awaitEvent("expired k");
	}

	/**
	 * The reason this backend needs no sweeper: a key nobody comes back to is removed by
	 * etcd itself when its lease runs out, and the watch turns that into the expiry event
	 * an application's {@code SessionExpiredEvent} is made of.
	 */
	@Test
	void aKeyNobodyTouchesIsExpiredByEtcdItself() {
		this.store.hset(b("abandoned"), fields("a", "1"));
		this.store.expireAt(b("abandoned"), this.store.currentTimeMillis() + 1_000);

		this.listener.awaitEvent("expired abandoned");

		assertThat(this.store.exists(b("abandoned"))).isFalse();
	}

	@Test
	void mutatingAKeyDoesNotChangeItsDeadline() {
		this.store.hset(b("h"), fields("a", "1"));
		long deadline = this.store.currentTimeMillis() + 60_000;
		this.store.expireAt(b("h"), deadline);

		this.store.hset(b("h"), fields("b", "2"));

		assertThat(this.store.getExpireAt(b("h"))).isEqualTo(deadline);
		assertThat(asHash(this.store.get(b("h"))).fields()).hasSize(2);
	}

	/**
	 * Writing to a key whose deadline has passed expires it first and creates it second,
	 * as Redis does, so the session that was there is announced as expired rather than
	 * silently overwritten.
	 */
	@Test
	void writingToAnOverdueKeyExpiresItFirst() {
		this.store.hset(b("h"), fields("a", "1"));
		this.store.expireAt(b("h"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.hset(b("h"), fields("b", "2"))).isEqualTo(1);

		this.listener.awaitEvent("expired h");
		assertThat(asHash(this.store.get(b("h"))).fields()).containsOnlyKeys(key("b"));
		assertThat(this.store.getExpireAt(b("h"))).isNull();
	}

	// --- RENAME ------------------------------------------------------------------------

	/**
	 * A rename moves the value and its deadline and announces nothing at all. Spring
	 * Session changes a session's id by renaming its keys, and a {@code del} on the way
	 * would be read by every subscribed application as that session having been
	 * destroyed.
	 */
	@Test
	void renameMovesTheValueAndItsDeadlineInSilence() {
		this.store.hset(b("from"), fields("a", "1"));
		long deadline = this.store.currentTimeMillis() + 60_000;
		this.store.expireAt(b("from"), deadline);

		assertThat(this.store.rename(b("from"), b("to"))).isTrue();

		assertThat(this.store.exists(b("from"))).isFalse();
		assertThat(asHash(this.store.get(b("to"))).fields()).containsOnlyKeys(key("a"));
		assertThat(this.store.getExpireAt(b("to"))).isEqualTo(deadline);
		this.listener.assertSilence(Duration.ofSeconds(1));
	}

	@Test
	void renameOfAnAbsentKeyReportsFailure() {
		assertThat(this.store.rename(b("from"), b("to"))).isFalse();
		assertThat(this.store.exists(b("to"))).isFalse();
	}

	@Test
	void renameOverwritesTheDestination() {
		this.store.append(b("from"), b("new"));
		this.store.append(b("to"), b("old"));

		assertThat(this.store.rename(b("from"), b("to"))).isTrue();

		assertThat(asString(this.store.get(b("to"))).value()).containsExactly(b("new"));
	}

	@Test
	void renameOfAnOverdueKeyFailsAndAnnouncesTheExpiry() {
		this.store.append(b("from"), b("v"));
		this.store.expireAt(b("from"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.rename(b("from"), b("to"))).isFalse();

		assertThat(this.store.exists(b("to"))).isFalse();
		this.listener.awaitEvent("expired from");
	}

	/**
	 * The source of a rename is left as a tombstone for a moment before it is removed,
	 * and whatever a session's new id is, that key has to be usable again straight away.
	 */
	@Test
	void aKeyThatWasJustRenamedAwayCanBeWrittenAgain() {
		this.store.append(b("from"), b("v"));
		this.store.rename(b("from"), b("to"));

		assertThat(this.store.append(b("from"), b("again"))).isEqualTo(5);
		assertThat(asString(this.store.get(b("from"))).value()).containsExactly(b("again"));
	}

	// --- SEVERAL ADAPTERS --------------------------------------------------------------

	/**
	 * What this backend is for. Two adapter replicas share one etcd keyspace, so a
	 * session written to one is readable from the other, and — the part a shared map
	 * alone would not give — a key one replica removes is announced to the clients
	 * subscribed to the other. Without this, an application connected to replica A never
	 * hears that replica B expired its session.
	 */
	@Test
	void anotherReplicaSeesTheSameKeysAndHearsAboutTheOnesItDidNotRemove() {
		try (EtcdKeyValueStore other = store(this.store.keyPrefix())) {
			RecordingListener elsewhere = new RecordingListener();
			other.addKeyEventListener(elsewhere);

			this.store.hset(b("shared"), fields("a", "1"));
			assertThat(asHash(other.get(b("shared"))).fields()).containsOnlyKeys(key("a"));

			other.delete(b("shared"));

			this.listener.awaitEvent("deleted shared");
			elsewhere.awaitEvent("deleted shared");
		}
	}

	@Test
	void anExpiryIsAnnouncedToEveryReplicaWhicheverOneWroteTheKey() {
		try (EtcdKeyValueStore other = store(this.store.keyPrefix())) {
			RecordingListener elsewhere = new RecordingListener();
			other.addKeyEventListener(elsewhere);

			this.store.append(b("dying"), new byte[0]);
			this.store.expireAt(b("dying"), this.store.currentTimeMillis() + 1_000);

			elsewhere.awaitEvent("expired dying");
		}
	}

	@Test
	void aRenameByAnotherReplicaIsSilentThereToo() {
		try (EtcdKeyValueStore other = store(this.store.keyPrefix())) {
			RecordingListener elsewhere = new RecordingListener();
			other.addKeyEventListener(elsewhere);
			this.store.append(b("from"), b("v"));

			other.rename(b("from"), b("to"));

			assertThat(this.store.exists(b("to"))).isTrue();
			this.listener.assertSilence(Duration.ofSeconds(1));
			elsewhere.assertSilence(Duration.ofMillis(100));
		}
	}

	// --- CONCURRENCY -------------------------------------------------------------------

	/**
	 * Two replicas adding to one set is the ordinary case for the principal index, and a
	 * read-modify-write that is not guarded would lose members. Every update here is a
	 * transaction on the revision it read, retried when it loses, so all of them land.
	 */
	@Test
	void concurrentAddsToOneSetAllLand() throws Exception {
		int writers = 8;
		int perWriter = 10;
		try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
			List<Future<?>> writes = new ArrayList<>();
			for (int writer = 0; writer < writers; writer++) {
				int id = writer;
				writes.add(pool.submit(() -> {
					for (int i = 0; i < perWriter; i++) {
						this.store.sadd(b("contended"), List.of(b("m-" + id + "-" + i)));
					}
				}));
			}
			for (Future<?> write : writes) {
				write.get();
			}
		}

		assertThat(asSet(this.store.get(b("contended"))).members()).hasSize(writers * perWriter);
	}

	/**
	 * The contended key a real deployment has: every session expiring in the same minute
	 * adds itself to that minute's set, from as many callers as the adapters sharing the
	 * cluster have connections between them. Compare-and-swap alone does not survive this
	 * — the work per successful write grows with the number of writers, so past some
	 * concurrency the retries stop keeping up and a share of the writes is lost — which
	 * is why the mutations of one key are applied a batch at a time instead.
	 */
	@Test
	void everyWriteLandsWhenHundredsOfCallersShareOneKey() {
		int writers = 256;
		int perWriter = 5;
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int writer = 0; writer < writers; writer++) {
				int id = writer;
				pool.submit(() -> {
					for (int i = 0; i < perWriter; i++) {
						try {
							this.store.sadd(b("expirations"), List.of(b("m-" + id + "-" + i)));
						}
						catch (RuntimeException e) {
							failures.add(e);
						}
					}
				});
			}
		}

		assertThat(failures).isEmpty();
		assertThat(asSet(this.store.get(b("expirations"))).members()).hasSize(writers * perWriter);
	}

	/**
	 * Spring Session sets the bucket's deadline on every save, right after adding to it,
	 * so a deadline and a batch of additions are the ordinary contended pair. Neither may
	 * lose to the other.
	 */
	@Test
	void aDeadlineSetWhileTheSameKeyIsBeingWrittenLands() {
		int writers = 64;
		long deadline = this.store.currentTimeMillis() + Duration.ofMinutes(30).toMillis();
		this.store.sadd(b("expirations"), List.of(b("first")));
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int writer = 0; writer < writers; writer++) {
				int id = writer;
				pool.submit(() -> {
					try {
						this.store.sadd(b("expirations"), List.of(b("m-" + id)));
						this.store.expireAt(b("expirations"), deadline);
					}
					catch (RuntimeException e) {
						failures.add(e);
					}
				});
			}
		}

		assertThat(failures).isEmpty();
		assertThat(asSet(this.store.get(b("expirations"))).members()).hasSize(writers + 1);
		assertThat(this.store.getExpireAt(b("expirations"))).isEqualTo(deadline);
	}

	/**
	 * A mutation that refuses the value fails the caller that asked for it and nobody
	 * else: a batch is a convenience of this backend's, not something an application can
	 * be made to notice.
	 */
	@Test
	void aMutationThatRefusesTheValueFailsOnlyItsOwnCaller() {
		int writers = 64;
		this.store.sadd(b("s"), List.of(b("first")));
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int writer = 0; writer < writers; writer++) {
				int id = writer;
				pool.submit(() -> {
					this.store.sadd(b("s"), List.of(b("m-" + id)));
					try {
						this.store.append(b("s"), b("not a string"));
					}
					catch (RuntimeException e) {
						failures.add(e);
					}
				});
			}
		}

		assertThat(failures).hasSize(writers)
			.allSatisfy(failure -> assertThat(failure).isInstanceOf(TypeMismatchException.class)
				.hasMessageContaining("APPEND"));
		assertThat(asSet(this.store.get(b("s"))).members()).hasSize(writers + 1);
	}

	@Test
	void concurrentFieldWritesToOneHashAllLand() throws Exception {
		int writers = 8;
		try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
			List<Future<?>> writes = IntStream.range(0, writers)
				.<Future<?>>mapToObj(writer -> pool
					.submit(() -> this.store.hset(b("contended"), fields("f-" + writer, String.valueOf(writer)))))
				.toList();
			for (Future<?> write : writes) {
				write.get();
			}
		}

		assertThat(asHash(this.store.get(b("contended"))).fields()).hasSize(writers);
	}

	// --- KEYSPACE ----------------------------------------------------------------------

	/**
	 * Each database is an independent keyspace, which for this backend means a prefix of
	 * its own — including the events, or an application on database 0 would hear about
	 * database 1's sessions.
	 */
	@Test
	void twoPrefixesAreTwoKeyspaces() {
		try (EtcdKeyValueStore other = store(sibling(this.store.keyPrefix()))) {
			RecordingListener elsewhere = new RecordingListener();
			other.addKeyEventListener(elsewhere);

			this.store.append(b("k"), b("mine"));

			assertThat(other.get(b("k"))).isNull();

			other.append(b("k"), b("theirs"));
			other.delete(b("k"));

			elsewhere.awaitEvent("deleted k");
			assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("mine"));
			this.listener.assertSilence(Duration.ofMillis(500));
		}
	}

	/**
	 * The databases are numbered, so their prefixes end in a number, and the store of
	 * database 1 must not see database 11 — which is what a prefix range would do if the
	 * separator were left off the end.
	 */
	@Test
	void aDatabaseWhoseNumberStartsWithAnothersIsStillItsOwnKeyspace() {
		try (EtcdKeyValueStore one = store("/numbered/1/"); EtcdKeyValueStore eleven = store("/numbered/11/")) {
			RecordingListener elsewhere = new RecordingListener();
			one.addKeyEventListener(elsewhere);

			eleven.append(b("k"), b("v"));
			eleven.delete(b("k"));

			assertThat(one.exists(b("k"))).isFalse();
			elsewhere.assertSilence(Duration.ofMillis(500));
		}
	}

	@Test
	void closeIsIdempotent() {
		this.store.close();
		this.store.close();
	}

	// --- helpers -----------------------------------------------------------------------

	/**
	 * Returns the etcd lease a key is attached to, which is what says whether a deadline
	 * was pushed out by renewing the lease that was there or by granting another.
	 * @param key the Redis key
	 * @return the lease id, or {@code 0} if the key is on no lease
	 */
	private long leaseOf(byte[] key) {
		byte[] etcdKey = b(this.store.keyPrefix() + new String(key, UTF_8));
		return requireNonNull(this.etcd.get(etcdKey), "no such key").lease();
	}

	/**
	 * Returns every lease the cluster currently holds. A lease this backend granted and
	 * then left holding nothing would show up here, which is the leak a renewal must not
	 * introduce.
	 * @return the lease ids
	 */
	private Set<Long> leases() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(EtcdCluster.endpoint() + "/v3/lease/leases"))
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			return Json.array(Json.parseObject(response.body()).get("leases"))
				.stream()
				.map(lease -> Json.integer(requireNonNull(Json.object(lease)).get("ID"), 0L))
				.collect(Collectors.toSet());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

	/**
	 * Returns a prefix beside the given one rather than inside it. A prefix that is the
	 * start of another is one keyspace by definition — which is why the databases are
	 * separated by a number and a separator, never by nesting.
	 * @param prefix the prefix to sit beside
	 * @return a prefix that shares no keys with it
	 */
	private static String sibling(String prefix) {
		return prefix.substring(0, prefix.length() - 1) + "-other/";
	}

	private static ByteArrayKey key(String text) {
		return ByteArrayKey.of(b(text));
	}

	private static Map<byte[], byte[]> fields(String... namesAndValues) {
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			fields.put(b(namesAndValues[i]), b(namesAndValues[i + 1]));
		}
		return fields;
	}

	private static Map<byte[], Double> scores(Object... membersAndScores) {
		Map<byte[], Double> scores = new LinkedHashMap<>();
		for (int i = 0; i < membersAndScores.length; i += 2) {
			scores.put(b((String) membersAndScores[i]), (Double) membersAndScores[i + 1]);
		}
		return scores;
	}

	private static StringValue asString(@Nullable RedisValue value) {
		return (StringValue) requireNonNull(value, "no value under the key");
	}

	private static HashValue asHash(@Nullable RedisValue value) {
		return (HashValue) requireNonNull(value, "no value under the key");
	}

	private static SetValue asSet(@Nullable RedisValue value) {
		return (SetValue) requireNonNull(value, "no value under the key");
	}

	private static ZSetValue asZSet(@Nullable RedisValue value) {
		return (ZSetValue) requireNonNull(value, "no value under the key");
	}

}
