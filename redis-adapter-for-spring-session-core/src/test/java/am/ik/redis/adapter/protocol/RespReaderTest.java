package am.ik.redis.adapter.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RespReaderTest {

	@Test
	void parsesMultibulkRequest() throws IOException {
		RespReader reader = reader("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n");
		assertArgv(reader.readCommand(), b("SET"), b("a"), b("b"));
	}

	@Test
	void reassemblesRequestSplitAcrossReads() throws IOException {
		byte[] wire = "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n".getBytes(US_ASCII);
		RespReader reader = new RespReader(new ChunkedInputStream(wire, 1)); // one byte
																				// per
																				// underlying
																				// read
		assertArgv(reader.readCommand(), b("SET"), b("a"), b("b"));
	}

	@Test
	void bulkArgumentIsBinarySafe() throws IOException {
		byte[] binary = { 0, 13, 10, -1 }; // NUL CR LF 0xFF
		byte[] wire = concat("*2\r\n$3\r\nSET\r\n$4\r\n".getBytes(US_ASCII), binary, "\r\n".getBytes(US_ASCII));
		RespReader reader = new RespReader(new ByteArrayInputStream(wire));
		List<byte[]> argv = requireNonNull(reader.readCommand());
		assertThat(argv).hasSize(2);
		assertThat(argv.get(1)).containsExactly(binary);
	}

	@Test
	void parsesZeroLengthBulkArgument() throws IOException {
		RespReader reader = reader("*3\r\n$6\r\nAPPEND\r\n$1\r\nk\r\n$0\r\n\r\n");
		List<byte[]> argv = requireNonNull(reader.readCommand());
		assertThat(argv).hasSize(3);
		assertThat(argv.get(2)).isEmpty(); // empty byte[0], not null
	}

	@Test
	void returnsNullOnCleanEof() throws IOException {
		assertThat(reader("").readCommand()).isNull();
	}

	@Test
	void readsMultiplePipelinedCommandsThenEof() throws IOException {
		RespReader reader = reader("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");
		assertArgv(reader.readCommand(), b("PING"));
		assertArgv(reader.readCommand(), b("PING"));
		assertThat(reader.readCommand()).isNull();
	}

	@Test
	void skipsEmptyAndNegativeMultibulkHeaders() throws IOException {
		RespReader reader = reader("*0\r\n*-1\r\n*1\r\n$4\r\nPING\r\n");
		assertArgv(reader.readCommand(), b("PING"));
	}

	@Test
	void parsesInlineCommands() throws IOException {
		RespReader reader = reader("PING\r\nSET a b\r\n");
		assertArgv(reader.readCommand(), b("PING"));
		assertArgv(reader.readCommand(), b("SET"), b("a"), b("b"));
	}

	@Test
	void rejectsInlineCommandTruncatedByEof() {
		// A client that disconnects mid-line must not have its partial command executed.
		assertProtocolError("GET fo");
	}

	@Test
	void rejectsMissingBulkMarker() {
		assertProtocolError("*1\r\nxPING\r\n");
	}

	@Test
	void rejectsNegativeBulkLength() {
		assertProtocolError("*1\r\n$-1\r\n");
	}

	@Test
	void rejectsBrokenCrlfAfterPayload() {
		assertProtocolError("*1\r\n$1\r\nab\r\n"); // declares 1 byte then "ab" instead of
													// "a\r\n"
	}

	@Test
	void rejectsNonDigitLength() {
		assertProtocolError("*1\r\n$x\r\n");
	}

	@Test
	void rejectsLeadingZeroLength() {
		assertProtocolError("*1\r\n$01\r\n\r\n");
	}

	@Test
	void rejectsIntegerOverflow() {
		assertProtocolError("*1\r\n$99999999999999999999\r\n");
		assertProtocolError("*99999999999999999999\r\n");
	}

	@Test
	void enforcesMaxBulkLength() {
		RespReader reader = RespReader.builder(stream("*1\r\n$5\r\nhello\r\n")).maxBulkLength(4).build();
		assertThatExceptionOfType(RespProtocolException.class).isThrownBy(reader::readCommand);
	}

	@Test
	void roundTripsWithWriter() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		new RespWriter(buffer).writeBulkArray(List.of(b("SET"), b("a"), b("b")));
		RespReader reader = new RespReader(new ByteArrayInputStream(buffer.toByteArray()));
		assertArgv(reader.readCommand(), b("SET"), b("a"), b("b"));
	}

	// --- helpers -----------------------------------------------------------------------

	private static byte[] b(String s) {
		return s.getBytes(US_ASCII);
	}

	private static RespReader reader(String wire) {
		return new RespReader(stream(wire));
	}

	private static InputStream stream(String wire) {
		return new ByteArrayInputStream(wire.getBytes(US_ASCII));
	}

	private static void assertArgv(@Nullable List<byte[]> argv, byte[]... expected) {
		assertThat(argv).isNotNull();
		List<byte[]> actual = requireNonNull(argv);
		assertThat(actual).hasSize(expected.length);
		for (int i = 0; i < expected.length; i++) {
			assertThat(actual.get(i)).containsExactly(expected[i]);
		}
	}

	private static void assertProtocolError(String wire) {
		assertThatExceptionOfType(RespProtocolException.class).isThrownBy(() -> reader(wire).readCommand());
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

	/**
	 * Delivers at most {@code chunk} bytes per read to exercise partial-read reassembly.
	 */
	private static final class ChunkedInputStream extends InputStream {

		private final byte[] data;

		private final int chunk;

		private int position;

		ChunkedInputStream(byte[] data, int chunk) {
			this.data = data;
			this.chunk = chunk;
		}

		@Override
		public int read() {
			return (this.position < this.data.length) ? (this.data[this.position++] & 0xFF) : -1;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (this.position >= this.data.length) {
				return -1;
			}
			int n = Math.min(Math.min(len, this.chunk), this.data.length - this.position);
			System.arraycopy(this.data, this.position, b, off, n);
			this.position += n;
			return n;
		}

	}

}
