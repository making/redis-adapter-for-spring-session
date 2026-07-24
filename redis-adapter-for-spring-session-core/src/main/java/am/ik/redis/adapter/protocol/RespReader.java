package am.ik.redis.adapter.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Reads RESP requests from an {@link InputStream}.
 *
 * <p>
 * A client request is always a <em>multibulk</em>: an array of bulk strings
 * ({@code *<n>\r\n} then {@code n} times {@code $<len>\r\n<bytes>\r\n}).
 * {@link #readCommand()} returns the next request as its argument vector
 * ({@code List<byte[]>}, argv), where {@code argv[0]} is the command name and the rest
 * are raw argument bytes. Everything is bytes; nothing is ever decoded to a
 * {@code String}.
 *
 * <p>
 * The reader is strict about framing and raises {@link RespProtocolException} on any
 * violation, but tolerant of legacy inline commands (whitespace-separated, no leading
 * {@code *}) which some manual clients such as {@code telnet} use. It buffers internally
 * so a request split across multiple underlying reads is reassembled transparently, and
 * it guards against hostile inputs (oversized lengths, integer overflow, unbounded
 * tokens).
 *
 * <p>
 * Instances are not thread-safe; use one reader per connection.
 */
public final class RespReader {

	/**
	 * Default cap on a single bulk payload (Redis {@code proto-max-bulk-len} default: 512
	 * MiB).
	 */
	public static final int DEFAULT_MAX_BULK_LENGTH = 512 * 1024 * 1024;

	/**
	 * Default cap on the element count of a request array (Redis multibulk limit: 1 Mi).
	 */
	public static final int DEFAULT_MAX_ARRAY_ELEMENTS = 1024 * 1024;

	/** Cap on an inline command line, as defense against unbounded buffering. */
	private static final int MAX_INLINE_LENGTH = 64 * 1024;

	/** Cap on the digit run of a length/count token (a {@code long} needs at most 19). */
	private static final int MAX_NUMBER_DIGITS = 20;

	private final InputStream in;

	private final int maxBulkLength;

	private final int maxArrayElements;

	/**
	 * Creates a reader with default limits.
	 * @param in the source stream (wrapped in a {@link BufferedInputStream} if not
	 * already buffered)
	 */
	public RespReader(InputStream in) {
		this(in, DEFAULT_MAX_BULK_LENGTH, DEFAULT_MAX_ARRAY_ELEMENTS);
	}

	private RespReader(InputStream in, int maxBulkLength, int maxArrayElements) {
		this.in = (in instanceof BufferedInputStream buffered) ? buffered : new BufferedInputStream(in);
		this.maxBulkLength = maxBulkLength;
		this.maxArrayElements = maxArrayElements;
	}

	/**
	 * Returns a builder for customizing the size limits.
	 * @param in the source stream
	 * @return a new builder
	 */
	public static Builder builder(InputStream in) {
		return new Builder(in);
	}

	/**
	 * Reads and returns the next request as an argument vector, or {@code null} at a
	 * clean end of stream (the peer closed the connection at a command boundary). Empty
	 * or negative multibulk headers and blank inline lines are skipped (matching Redis's
	 * reset-and-continue behaviour) rather than returned.
	 * @return the next request's argv, or {@code null} on clean EOF
	 * @throws IOException if the underlying stream fails
	 * @throws RespProtocolException if the bytes are malformed
	 */
	public @Nullable List<byte[]> readCommand() throws IOException {
		while (true) {
			int marker = this.in.read();
			if (marker == -1) {
				return null; // clean EOF at a frame boundary
			}
			if (marker == '*') {
				long count = readSignedNumber();
				if (count <= 0) {
					continue; // empty/negative multibulk: reset and read the next frame
				}
				if (count > this.maxArrayElements) {
					throw new RespProtocolException("multibulk element count exceeds limit: " + count);
				}
				return readBulkArray((int) count);
			}
			List<byte[]> inline = readInline(marker);
			if (inline.isEmpty()) {
				continue; // blank inline line
			}
			return inline;
		}
	}

	private List<byte[]> readBulkArray(int count) throws IOException {
		List<byte[]> argv = new ArrayList<>(Math.min(count, 1024));
		for (int i = 0; i < count; i++) {
			int type = this.in.read();
			if (type == -1) {
				throw new RespProtocolException("unexpected end of stream before bulk element " + i);
			}
			if (type != '$') {
				throw new RespProtocolException("expected '$' bulk marker but got 0x" + Integer.toHexString(type));
			}
			long length = readSignedNumber();
			if (length < 0) {
				throw new RespProtocolException("negative bulk length in request: " + length);
			}
			if (length > this.maxBulkLength) {
				throw new RespProtocolException("bulk length exceeds limit: " + length);
			}
			argv.add(readFully((int) length));
			expectCrlf();
		}
		return argv;
	}

	/**
	 * Reads a signed decimal integer terminated by CRLF, with the leading type byte
	 * already consumed. Rejects an empty token, a leading {@code +}, embedded
	 * signs/non-digits, a leading zero (except a lone {@code 0}), {@code -0}, an
	 * over-long digit run, and any value that would overflow a {@code long}.
	 */
	private long readSignedNumber() throws IOException {
		int c = this.in.read();
		if (c == -1) {
			throw new RespProtocolException("unexpected end of stream in integer");
		}
		boolean negative = false;
		if (c == '-') {
			negative = true;
			c = this.in.read();
		}
		if (c < '0' || c > '9') {
			throw new RespProtocolException("invalid integer: no digits");
		}
		boolean leadingZero = (c == '0');
		long value = c - '0';
		int digits = 1;
		while (true) {
			c = this.in.read();
			if (c == '\r') {
				break;
			}
			if (c == -1) {
				throw new RespProtocolException("unexpected end of stream in integer");
			}
			if (c < '0' || c > '9') {
				throw new RespProtocolException("invalid integer digit: 0x" + Integer.toHexString(c));
			}
			if (leadingZero) {
				throw new RespProtocolException("invalid integer with leading zero");
			}
			if (++digits > MAX_NUMBER_DIGITS || value > (Long.MAX_VALUE - (c - '0')) / 10) {
				throw new RespProtocolException("integer overflow");
			}
			value = value * 10 + (c - '0');
		}
		if (this.in.read() != '\n') {
			throw new RespProtocolException("expected LF after CR in integer");
		}
		if (negative) {
			if (leadingZero) {
				throw new RespProtocolException("invalid negative zero");
			}
			return -value;
		}
		return value;
	}

	private byte[] readFully(int length) throws IOException {
		byte[] buffer = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = this.in.read(buffer, offset, length - offset);
			if (read == -1) {
				throw new RespProtocolException("unexpected end of stream in bulk payload");
			}
			offset += read;
		}
		return buffer;
	}

	private void expectCrlf() throws IOException {
		int cr = this.in.read();
		int lf = this.in.read();
		if (cr != '\r' || lf != '\n') {
			throw new RespProtocolException("expected CRLF after bulk payload");
		}
	}

	/**
	 * Parses a legacy inline command: the rest of the current line (the first byte
	 * already read), split on spaces and tabs. No quoting is interpreted. A blank line
	 * yields an empty list. An unterminated line ending at end of stream is a truncated
	 * command and is rejected, so a client that disconnects mid-line never has its
	 * partial command materialized (matching Redis, and the strict mid-frame behaviour of
	 * the multibulk path).
	 */
	private List<byte[]> readInline(int firstByte) throws IOException {
		List<byte[]> args = new ArrayList<>();
		ByteArrayOutputStream token = new ByteArrayOutputStream();
		boolean inToken = false;
		int c = firstByte;
		int lineLength = 0;
		while (c != -1 && c != '\n') {
			if (++lineLength > MAX_INLINE_LENGTH) {
				throw new RespProtocolException("inline command line too long");
			}
			if (c == '\r') {
				c = this.in.read();
				continue;
			}
			if (c == ' ' || c == '\t') {
				if (inToken) {
					args.add(token.toByteArray());
					token.reset();
					inToken = false;
				}
			}
			else {
				token.write(c);
				inToken = true;
			}
			c = this.in.read();
		}
		if (c == -1) {
			throw new RespProtocolException("unexpected end of stream in inline command");
		}
		if (inToken) {
			args.add(token.toByteArray());
		}
		return args;
	}

	/**
	 * Builder for {@link RespReader} size limits.
	 */
	public static final class Builder {

		private final InputStream in;

		private int maxBulkLength = DEFAULT_MAX_BULK_LENGTH;

		private int maxArrayElements = DEFAULT_MAX_ARRAY_ELEMENTS;

		private Builder(InputStream in) {
			this.in = in;
		}

		/**
		 * Sets the maximum accepted bulk-string length in bytes.
		 * @param maxBulkLength the limit (must be positive)
		 * @return this builder
		 */
		public Builder maxBulkLength(int maxBulkLength) {
			if (maxBulkLength <= 0) {
				throw new IllegalArgumentException("maxBulkLength must be positive: " + maxBulkLength);
			}
			this.maxBulkLength = maxBulkLength;
			return this;
		}

		/**
		 * Sets the maximum accepted request-array element count.
		 * @param maxArrayElements the limit (must be positive)
		 * @return this builder
		 */
		public Builder maxArrayElements(int maxArrayElements) {
			if (maxArrayElements <= 0) {
				throw new IllegalArgumentException("maxArrayElements must be positive: " + maxArrayElements);
			}
			this.maxArrayElements = maxArrayElements;
			return this;
		}

		/**
		 * Builds the reader.
		 * @return a new reader
		 */
		public RespReader build() {
			return new RespReader(this.in, this.maxBulkLength, this.maxArrayElements);
		}

	}

}
