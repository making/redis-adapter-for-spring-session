package am.ik.redis.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RespVersionTest {

	@Test
	void fromNumberResolvesSupportedVersions() {
		assertThat(RespVersion.fromNumber(2)).isEqualTo(RespVersion.RESP2);
		assertThat(RespVersion.fromNumber(3)).isEqualTo(RespVersion.RESP3);
	}

	@Test
	void fromNumberRejectsUnsupportedVersions() {
		for (int unsupported : new int[] { -1, 0, 1, 4, 100 }) {
			assertThatIllegalArgumentException().isThrownBy(() -> RespVersion.fromNumber(unsupported));
		}
	}

	@Test
	void exposesProtocolNumber() {
		assertThat(RespVersion.RESP2.number()).isEqualTo(2);
		assertThat(RespVersion.RESP3.number()).isEqualTo(3);
	}

}
