package am.ik.redis.adapter.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Writes RESP replies to an {@link OutputStream}.
 *
 * <p>
 * The writer offers one method per reply type. Composite replies (arrays, maps, pushes)
 * are written as a header followed by the caller writing each element, which keeps the
 * codec decoupled from command semantics and lets the caller mix element types freely
 * (for example a subscribe confirmation of {@code [bulk, bulk, integer]}).
 *
 * <p>
 * Encoding of nulls, maps, pushes, doubles and booleans depends on the negotiated
 * {@link RespVersion}. The version is mutable so the connection can upgrade it after
 * {@code HELLO 3}; the upgrade must be applied <em>before</em> the {@code HELLO} reply is
 * written.
 *
 * <p>
 * The writer does not buffer or flush per call — wrap the stream in a
 * {@link java.io.BufferedOutputStream} and {@link #flush()} once per complete reply.
 * Instances are not thread-safe: the connection must serialize all writes of a single
 * reply frame (and the trailing {@code flush()}) under its per-connection write lock, and
 * must not change the protocol version mid-frame, otherwise a concurrent pub/sub push
 * could interleave bytes or split a frame across versions.
 */
public final class RespWriter {

	private static final byte[] CRLF = { '\r', '\n' };

	private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] NULL_RESP3 = "_\r\n".getBytes(StandardCharsets.US_ASCII);

	private final OutputStream out;

	private volatile RespVersion version;

	/**
	 * Creates a writer defaulting to {@link RespVersion#RESP2}.
	 * @param out the destination stream
	 */
	public RespWriter(OutputStream out) {
		this(out, RespVersion.RESP2);
	}

	/**
	 * Creates a writer with an explicit initial version.
	 * @param out the destination stream
	 * @param version the initial protocol version
	 */
	public RespWriter(OutputStream out, RespVersion version) {
		this.out = out;
		this.version = version;
	}

	/**
	 * Returns the current protocol version.
	 * @return the version
	 */
	public RespVersion protocolVersion() {
		return this.version;
	}

	/**
	 * Sets the protocol version (for example after a successful {@code HELLO 3}).
	 * @param version the new version
	 * @return this writer
	 */
	public RespWriter protocolVersion(RespVersion version) {
		this.version = version;
		return this;
	}

	/**
	 * Writes a simple string reply ({@code +...\r\n}).
	 * @param value the ASCII payload, which must not contain CR or LF (simple strings are
	 * line-framed); untrusted bytes must go through {@link #writeBulk(byte[])} instead
	 * @throws IOException if the stream fails
	 * @throws IllegalArgumentException if {@code value} contains CR or LF
	 */
	public void writeSimpleString(String value) throws IOException {
		ensureNoCrlf(value);
		this.out.write('+');
		this.out.write(value.getBytes(StandardCharsets.US_ASCII));
		this.out.write(CRLF);
	}

	/**
	 * Writes an error reply ({@code -...\r\n}). The message conventionally starts with an
	 * uppercase code such as {@code ERR} or {@code WRONGTYPE}.
	 * @param message the ASCII error message, which must not contain CR or LF
	 * @throws IOException if the stream fails
	 * @throws IllegalArgumentException if {@code message} contains CR or LF
	 */
	public void writeError(String message) throws IOException {
		ensureNoCrlf(message);
		this.out.write('-');
		this.out.write(message.getBytes(StandardCharsets.US_ASCII));
		this.out.write(CRLF);
	}

	/**
	 * Writes an integer reply ({@code :<n>\r\n}).
	 * @param value the integer
	 * @throws IOException if the stream fails
	 */
	public void writeInteger(long value) throws IOException {
		this.out.write(':');
		writeAsciiNumber(value);
		this.out.write(CRLF);
	}

	/**
	 * Writes a bulk string reply, or a null bulk if {@code value} is {@code null}.
	 * @param value the payload bytes, or {@code null}
	 * @throws IOException if the stream fails
	 */
	public void writeBulk(byte @Nullable [] value) throws IOException {
		if (value == null) {
			writeNull();
			return;
		}
		writeBulk(value, 0, value.length);
	}

	/**
	 * Writes a bulk string reply from a sub-range of {@code value}.
	 * @param value the source array
	 * @param offset the start offset
	 * @param length the number of bytes
	 * @throws IOException if the stream fails
	 */
	public void writeBulk(byte[] value, int offset, int length) throws IOException {
		this.out.write('$');
		writeAsciiNumber(length);
		this.out.write(CRLF);
		this.out.write(value, offset, length);
		this.out.write(CRLF);
	}

	/**
	 * Writes a null: {@code $-1\r\n} in RESP2, {@code _\r\n} in RESP3.
	 * @throws IOException if the stream fails
	 */
	public void writeNull() throws IOException {
		this.out.write((this.version == RespVersion.RESP3) ? NULL_RESP3 : NULL_BULK);
	}

	/**
	 * Writes a RESP2 null array ({@code *-1\r\n}). In RESP3 prefer {@link #writeNull()}.
	 * @throws IOException if the stream fails
	 */
	public void writeNullArray() throws IOException {
		this.out.write((this.version == RespVersion.RESP3) ? NULL_RESP3 : NULL_ARRAY);
	}

	/**
	 * Writes an array header ({@code *<n>\r\n}); the caller then writes {@code count}
	 * elements.
	 * @param count the number of elements (non-negative)
	 * @throws IOException if the stream fails
	 * @throws IllegalArgumentException if {@code count} is negative
	 */
	public void writeArrayHeader(int count) throws IOException {
		if (count < 0) {
			throw new IllegalArgumentException("array count must be non-negative: " + count);
		}
		this.out.write('*');
		writeAsciiNumber(count);
		this.out.write(CRLF);
	}

	/**
	 * Writes a map header; the caller then writes {@code pairCount} key/value element
	 * pairs (that is, {@code 2 * pairCount} elements). In RESP3 this is
	 * {@code %<pairCount>\r\n}; in RESP2, which has no map type, it degrades to a flat
	 * array header {@code *<2*pairCount>\r\n}.
	 * @param pairCount the number of key/value pairs (non-negative)
	 * @throws IOException if the stream fails
	 * @throws IllegalArgumentException if {@code pairCount} is negative
	 */
	public void writeMapHeader(int pairCount) throws IOException {
		if (pairCount < 0) {
			throw new IllegalArgumentException("map pair count must be non-negative: " + pairCount);
		}
		if (this.version == RespVersion.RESP3) {
			this.out.write('%');
			writeAsciiNumber(pairCount);
		}
		else {
			this.out.write('*');
			writeAsciiNumber(2L * pairCount);
		}
		this.out.write(CRLF);
	}

	/**
	 * Writes a push header; the caller then writes {@code count} elements. In RESP3 this
	 * is {@code ><count>\r\n}; in RESP2, which has no push type, it degrades to an array
	 * header {@code *<count>\r\n} (this is how RESP2 pub/sub messages are framed).
	 * @param count the number of elements (non-negative)
	 * @throws IOException if the stream fails
	 * @throws IllegalArgumentException if {@code count} is negative
	 */
	public void writePushHeader(int count) throws IOException {
		if (count < 0) {
			throw new IllegalArgumentException("push count must be non-negative: " + count);
		}
		this.out.write((this.version == RespVersion.RESP3) ? '>' : '*');
		writeAsciiNumber(count);
		this.out.write(CRLF);
	}

	/**
	 * Writes a double. In RESP3 this is {@code ,<value>\r\n}; in RESP2 it degrades to a
	 * bulk string of the same text. Note that integer-valued doubles render as
	 * {@code "3.0"} rather than redis-server's {@code "3"}; this is off the Spring
	 * Session path and parsed numerically by clients.
	 * @param value the double
	 * @throws IOException if the stream fails
	 */
	public void writeDouble(double value) throws IOException {
		byte[] text = formatDouble(value);
		if (this.version == RespVersion.RESP3) {
			this.out.write(',');
			this.out.write(text);
			this.out.write(CRLF);
		}
		else {
			writeBulk(text, 0, text.length);
		}
	}

	/**
	 * Writes a boolean. In RESP3 this is {@code #t\r\n} / {@code #f\r\n}; in RESP2 it
	 * degrades to the integer {@code 1} / {@code 0}.
	 * @param value the boolean
	 * @throws IOException if the stream fails
	 */
	public void writeBoolean(boolean value) throws IOException {
		if (this.version == RespVersion.RESP3) {
			this.out.write('#');
			this.out.write(value ? 't' : 'f');
			this.out.write(CRLF);
		}
		else {
			writeInteger(value ? 1 : 0);
		}
	}

	/**
	 * Convenience for a flat array of (non-null) bulk strings: writes the header and each
	 * element. For arrays that must contain a null element, write the header and elements
	 * individually with {@link #writeArrayHeader(int)} and {@link #writeBulk(byte[])}.
	 * @param elements the bulk elements
	 * @throws IOException if the stream fails
	 */
	public void writeBulkArray(List<byte[]> elements) throws IOException {
		writeArrayHeader(elements.size());
		for (byte[] element : elements) {
			writeBulk(element);
		}
	}

	/**
	 * Flushes the underlying stream.
	 * @throws IOException if the stream fails
	 */
	public void flush() throws IOException {
		this.out.flush();
	}

	private void writeAsciiNumber(long value) throws IOException {
		this.out.write(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] formatDouble(double value) {
		String text;
		if (Double.isNaN(value)) {
			text = "nan";
		}
		else if (value == Double.POSITIVE_INFINITY) {
			text = "inf";
		}
		else if (value == Double.NEGATIVE_INFINITY) {
			text = "-inf";
		}
		else {
			text = Double.toString(value);
		}
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	private static void ensureNoCrlf(String value) {
		if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("simple string / error must not contain CR or LF");
		}
	}

}
