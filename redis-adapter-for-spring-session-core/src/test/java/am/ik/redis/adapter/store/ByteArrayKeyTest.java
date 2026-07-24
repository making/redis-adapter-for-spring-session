package am.ik.redis.adapter.store;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ByteArrayKeyTest {

	@Test
	void equalsAndHashCodeByContent() {
		ByteArrayKey a = ByteArrayKey.of(new byte[] { 1, 2, 3 });
		ByteArrayKey b = ByteArrayKey.of(new byte[] { 1, 2, 3 });
		assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
		assertThat(a).isNotEqualTo(ByteArrayKey.of(new byte[] { 1, 2, 4 }));
		assertThat(a).isNotEqualTo(ByteArrayKey.of(new byte[] { 1, 2 }));
	}

	@Test
	void copiesInputDefensively() {
		byte[] source = { 1, 2, 3 };
		ByteArrayKey key = ByteArrayKey.of(source);
		source[0] = 9;
		assertThat(key).isEqualTo(ByteArrayKey.of(new byte[] { 1, 2, 3 }));
	}

	@Test
	void returnsDefensiveCopy() {
		ByteArrayKey key = ByteArrayKey.of(new byte[] { 1, 2, 3 });
		byte[] out = key.asBytes();
		out[0] = 9;
		assertThat(key.asBytes()).containsExactly(1, 2, 3);
	}

	@Test
	void reportsLength() {
		assertThat(ByteArrayKey.of(new byte[] { 1, 2, 3 }).length()).isEqualTo(3);
		assertThat(ByteArrayKey.of(new byte[0]).length()).isZero();
	}

	@Test
	void usableAsMapKeyByValue() {
		Map<ByteArrayKey, String> map = new HashMap<>();
		map.put(ByteArrayKey.of(new byte[] { 10, 20 }), "v");
		assertThat(map).containsKey(ByteArrayKey.of(new byte[] { 10, 20 }));
		assertThat(map.get(ByteArrayKey.of(new byte[] { 10, 20 }))).isEqualTo("v");
	}

	@Test
	void toStringEscapesNonPrintableBytes() {
		assertThat(ByteArrayKey.of("key".getBytes()).toString()).contains("key");
		assertThat(ByteArrayKey.of(new byte[] { 0, 13, 10 }).toString()).contains("\\x00").contains("\\x0d");
	}

}
