package am.ik.redis.adapter.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RespWriterTest {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	@Test
	void simpleString() throws IOException {
		writer(RespVersion.RESP2).writeSimpleString("OK");
		assertBytes("+OK\r\n");
	}

	@Test
	void error() throws IOException {
		writer(RespVersion.RESP2).writeError("ERR no such key");
		assertBytes("-ERR no such key\r\n");
	}

	@Test
	void integers() throws IOException {
		RespWriter w = writer(RespVersion.RESP2);
		w.writeInteger(123);
		w.writeInteger(0);
		w.writeInteger(-1);
		assertBytes(":123\r\n:0\r\n:-1\r\n");
	}

	@Test
	void bulkString() throws IOException {
		writer(RespVersion.RESP2).writeBulk("foo".getBytes(US_ASCII));
		assertBytes("$3\r\nfoo\r\n");
	}

	@Test
	void bulkSubRange() throws IOException {
		writer(RespVersion.RESP2).writeBulk("XXfooYY".getBytes(US_ASCII), 2, 3);
		assertBytes("$3\r\nfoo\r\n");
	}

	@Test
	void emptyBulkVersusNullBulk() throws IOException {
		RespWriter w = writer(RespVersion.RESP2);
		w.writeBulk(new byte[0]);
		w.writeBulk((byte[]) null);
		assertBytes("$0\r\n\r\n$-1\r\n");
	}

	@Test
	void nullIsVersionAware() throws IOException {
		ByteArrayOutputStream resp3 = new ByteArrayOutputStream();
		new RespWriter(resp3, RespVersion.RESP3).writeNull();
		assertThat(resp3.toByteArray()).isEqualTo("_\r\n".getBytes(US_ASCII));

		writer(RespVersion.RESP2).writeNull();
		assertBytes("$-1\r\n");
	}

	@Test
	void nullArrayIsVersionAware() throws IOException {
		writer(RespVersion.RESP2).writeNullArray();
		assertBytes("*-1\r\n");
	}

	@Test
	void arrayHeaderThenElements() throws IOException {
		RespWriter w = writer(RespVersion.RESP2);
		w.writeArrayHeader(2);
		w.writeBulk("a".getBytes(US_ASCII));
		w.writeBulk("b".getBytes(US_ASCII));
		assertBytes("*2\r\n$1\r\na\r\n$1\r\nb\r\n");
	}

	@Test
	void bulkArrayConvenienceAndEmptyArray() throws IOException {
		writer(RespVersion.RESP2).writeBulkArray(List.of("a".getBytes(US_ASCII), "b".getBytes(US_ASCII)));
		assertBytes("*2\r\n$1\r\na\r\n$1\r\nb\r\n");

		this.out.reset();
		writer(RespVersion.RESP2).writeArrayHeader(0);
		assertBytes("*0\r\n");
	}

	@Test
	void mapHeaderDegradesToFlatArrayInResp2() throws IOException {
		writeHelloShapedMap(writer(RespVersion.RESP2));
		assertBytes("*4\r\n$6\r\nserver\r\n$5\r\nredis\r\n$5\r\nproto\r\n:3\r\n");
	}

	@Test
	void mapHeaderUsesPercentInResp3() throws IOException {
		writeHelloShapedMap(new RespWriter(this.out, RespVersion.RESP3));
		assertBytes("%2\r\n$6\r\nserver\r\n$5\r\nredis\r\n$5\r\nproto\r\n:3\r\n");
	}

	@Test
	void pushHeaderDegradesToArrayInResp2() throws IOException {
		writeSubscribeConfirmation(writer(RespVersion.RESP2));
		assertBytes("*3\r\n$9\r\nsubscribe\r\n$2\r\nch\r\n:1\r\n");
	}

	@Test
	void pushHeaderUsesGreaterThanInResp3() throws IOException {
		writeSubscribeConfirmation(new RespWriter(this.out, RespVersion.RESP3));
		assertBytes(">3\r\n$9\r\nsubscribe\r\n$2\r\nch\r\n:1\r\n");
	}

	@Test
	void doubleIsVersionAware() throws IOException {
		new RespWriter(this.out, RespVersion.RESP3).writeDouble(3.5);
		assertBytes(",3.5\r\n");

		this.out.reset();
		writer(RespVersion.RESP2).writeDouble(3.5);
		assertBytes("$3\r\n3.5\r\n");
	}

	@Test
	void booleanIsVersionAware() throws IOException {
		RespWriter resp3 = new RespWriter(this.out, RespVersion.RESP3);
		resp3.writeBoolean(true);
		resp3.writeBoolean(false);
		assertBytes("#t\r\n#f\r\n");

		this.out.reset();
		RespWriter resp2 = writer(RespVersion.RESP2);
		resp2.writeBoolean(true);
		resp2.writeBoolean(false);
		assertBytes(":1\r\n:0\r\n");
	}

	@Test
	void bulkIsBinarySafe() throws IOException {
		byte[] payload = { 0, 13, 10, -1 }; // NUL CR LF 0xFF
		writer(RespVersion.RESP2).writeBulk(payload);
		byte[] expected = concat("$4\r\n".getBytes(US_ASCII), payload, "\r\n".getBytes(US_ASCII));
		assertThat(this.out.toByteArray()).isEqualTo(expected);
	}

	@Test
	void liveVersionUpgradeChangesAllTypedEncodings() throws IOException {
		RespWriter w = writer(RespVersion.RESP2);
		w.writeNull();
		assertBytes("$-1\r\n");

		this.out.reset();
		w.protocolVersion(RespVersion.RESP3);
		w.writeNull();
		w.writeMapHeader(0);
		w.writePushHeader(0);
		w.writeDouble(1.5);
		w.writeBoolean(true);
		assertBytes("_\r\n%0\r\n>0\r\n,1.5\r\n#t\r\n");
	}

	@Test
	void simpleStringRejectsCrlfInjection() {
		assertThatIllegalArgumentException().isThrownBy(() -> writer(RespVersion.RESP2).writeSimpleString("a\r\nb"));
		assertThatIllegalArgumentException().isThrownBy(() -> writer(RespVersion.RESP2).writeError("a\nb"));
	}

	@Test
	void headerCountsRejectNegative() {
		assertThatIllegalArgumentException().isThrownBy(() -> writer(RespVersion.RESP2).writeArrayHeader(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> writer(RespVersion.RESP2).writeMapHeader(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> writer(RespVersion.RESP2).writePushHeader(-1));
	}

	// --- helpers -----------------------------------------------------------------------

	private RespWriter writer(RespVersion version) {
		return new RespWriter(this.out, version);
	}

	private void assertBytes(String expected) {
		assertThat(this.out.toByteArray()).isEqualTo(expected.getBytes(US_ASCII));
	}

	private static void writeHelloShapedMap(RespWriter w) throws IOException {
		w.writeMapHeader(2);
		w.writeBulk("server".getBytes(US_ASCII));
		w.writeBulk("redis".getBytes(US_ASCII));
		w.writeBulk("proto".getBytes(US_ASCII));
		w.writeInteger(3);
	}

	private static void writeSubscribeConfirmation(RespWriter w) throws IOException {
		w.writePushHeader(3);
		w.writeBulk("subscribe".getBytes(US_ASCII));
		w.writeBulk("ch".getBytes(US_ASCII));
		w.writeInteger(1);
	}

	private static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] p : parts) {
			total += p.length;
		}
		byte[] result = new byte[total];
		int offset = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, result, offset, p.length);
			offset += p.length;
		}
		return result;
	}

}
