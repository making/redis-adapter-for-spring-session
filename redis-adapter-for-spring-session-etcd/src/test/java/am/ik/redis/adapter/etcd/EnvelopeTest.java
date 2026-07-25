package am.ik.redis.adapter.etcd;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.ZSetValue;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * What a key holds has to come back exactly as it went in, whichever adapter wrote it.
 * The payloads are serialized session attributes, so a codec that is nearly right is a
 * session that nearly deserializes.
 */
class EnvelopeTest {

	@Test
	void aStringSurvivesTheRoundTrip() {
		Envelope envelope = Envelope.of(new StringValue(new byte[] { 0, 1, -1, 127, -128 }), 1234L, 60L);

		Envelope decoded = Envelope.decode(envelope.encode());

		assertThat(decoded.expireAtMillis()).isEqualTo(1234L);
		assertThat(((StringValue) decoded.requiredValue()).value()).containsExactly(0, 1, -1, 127, -128);
	}

	@Test
	void anEmptyStringIsNotAnAbsentOne() {
		Envelope decoded = Envelope
			.decode(Envelope.of(new StringValue(new byte[0]), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode());

		assertThat(decoded.isTombstone()).isFalse();
		assertThat(((StringValue) decoded.requiredValue()).value()).isEmpty();
	}

	@Test
	void aHashKeepsItsFieldsAndTheirOrder() {
		Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
		fields.put(key("z"), bytes("1"));
		fields.put(key("a"), bytes("2"));

		Envelope decoded = Envelope
			.decode(Envelope.of(new HashValue(fields), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode());

		HashValue hash = (HashValue) decoded.requiredValue();
		assertThat(hash.fields().keySet()).containsExactly(key("z"), key("a"));
		assertThat(hash.fields()).contains(entry(key("z"), bytes("1")), entry(key("a"), bytes("2")));
	}

	@Test
	void aSetKeepsItsMembers() {
		Set<ByteArrayKey> members = new LinkedHashSet<>(Set.of(key("a"), key("b")));

		Envelope decoded = Envelope.decode(Envelope.of(new SetValue(members), 7L, 1L).encode());

		assertThat(((SetValue) decoded.requiredValue()).members()).containsExactlyInAnyOrder(key("a"), key("b"));
	}

	@Test
	void aSortedSetKeepsItsScores() {
		Map<ByteArrayKey, Double> scores = new LinkedHashMap<>();
		scores.put(key("a"), 1.5);
		scores.put(key("b"), 1.7976931348623157E308);

		Envelope decoded = Envelope
			.decode(Envelope.of(new ZSetValue(scores), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode());

		assertThat(((ZSetValue) decoded.requiredValue()).scores()).containsOnly(entry(key("a"), 1.5),
				entry(key("b"), 1.7976931348623157E308));
	}

	@Test
	void aTombstoneHoldsNothing() {
		Envelope decoded = Envelope.decode(Envelope.tombstone().encode());

		assertThat(decoded.isTombstone()).isTrue();
		assertThatThrownBy(decoded::requiredValue).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aDeadlineIsPassedAtTheMillisecondItNames() {
		Envelope envelope = Envelope.of(new StringValue(new byte[0]), 1000L, Envelope.NO_LEASE_TTL);

		assertThat(envelope.isExpired(999L)).isFalse();
		assertThat(envelope.isExpired(1000L)).isTrue();
		assertThat(Envelope.of(new StringValue(new byte[0]), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL)
			.isExpired(Long.MAX_VALUE - 1)).isFalse();
	}

	/**
	 * The removal of a key is only ever one of three things, and this is where the
	 * difference between {@code SessionExpiredEvent}, {@code SessionDeletedEvent} and no
	 * event at all is decided.
	 */
	@Test
	void whatARemovalAnnouncesDependsOnWhatWasRemoved() {
		assertThat(Envelope.of(new StringValue(new byte[0]), 1000L, Envelope.NO_LEASE_TTL).removalEvent(1000L))
			.isEqualTo(Envelope.Removal.EXPIRED);
		assertThat(Envelope.of(new StringValue(new byte[0]), 1000L, Envelope.NO_LEASE_TTL).removalEvent(999L))
			.isEqualTo(Envelope.Removal.DELETED);
		assertThat(
				Envelope.of(new StringValue(new byte[0]), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).removalEvent(999L))
			.isEqualTo(Envelope.Removal.DELETED);
		assertThat(Envelope.tombstone().removalEvent(999L)).isNull();
	}

	/**
	 * The TTL of the key's lease travels with the key because the replica that pushes a
	 * deadline out is not necessarily the one that granted the lease, and renewing a
	 * lease rather than replacing it is only correct when that TTL is known.
	 */
	@Test
	void theTtlOfTheKeysLeaseSurvivesTheRoundTrip() {
		Envelope decoded = Envelope.decode(Envelope.of(new StringValue(bytes("v")), 1234L, 1800L).encode());

		assertThat(decoded.leaseTtlSeconds()).isEqualTo(1800L);
		assertThat(decoded.leaseRenewsTo(1800L)).isTrue();
		assertThat(decoded.leaseRenewsTo(1799L)).isFalse();
	}

	@Test
	void aKeyOnNoLeaseRenewsNothing() {
		Envelope onNoLease = Envelope.of(new StringValue(bytes("v")), 1234L, Envelope.NO_LEASE_TTL);

		assertThat(onNoLease.leaseRenewsTo(Envelope.NO_LEASE_TTL)).isFalse();
		assertThat(onNoLease.leaseRenewsTo(1800L)).isFalse();
	}

	@Test
	void aValueWrittenByAnotherFormatIsRefusedRatherThanMisread() {
		byte[] encoded = Envelope.of(new StringValue(bytes("v")), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode();
		encoded[0] = 99;

		assertThatThrownBy(() -> Envelope.decode(encoded)).isInstanceOf(EtcdException.class)
			.hasMessageContaining("Unknown value format");
	}

	private static byte[] bytes(String text) {
		return text.getBytes(UTF_8);
	}

	private static ByteArrayKey key(String text) {
		return ByteArrayKey.of(bytes(text));
	}

}
