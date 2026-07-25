package am.ik.redis.adapter.etcd;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The JSON this backend needs, and no more.
 *
 * <p>
 * etcd's gRPC gateway speaks JSON, so a backend that talks to it over plain HTTP has to
 * read and write some. This module has no dependencies, so it reads and writes its own:
 * the reader is a recursive-descent parser over the whole grammar, and the writer is a
 * builder for the few request shapes {@link EtcdClient} sends.
 *
 * <h2>How etcd renders values</h2> Bytes (keys, values, range bounds) are base64 in both
 * directions, and 64-bit fields (revisions, lease ids, TTLs) are <em>strings</em>,
 * because that is what the protobuf JSON mapping does with {@code int64}. Smaller numbers
 * (an error {@code code}) are plain JSON numbers. {@link #integer} therefore accepts
 * both, and every accessor tolerates a field etcd left out — a proto3 default is simply
 * absent from the response, so "the field is missing" is the normal case rather than an
 * error.
 *
 * <p>
 * A parsed document is made of {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} and {@link #NULL}. Reading it goes through the static accessors here
 * rather than by casting at each use.
 */
final class Json {

	/**
	 * The JSON {@code null} literal. A null-marked map cannot hold Java {@code null}, and
	 * a missing field and a null one mean the same thing to every accessor here.
	 */
	static final Object NULL = new Object() {
		@Override
		public String toString() {
			return "null";
		}
	};

	private final String text;

	private int index;

	private Json(String text) {
		this.text = text;
	}

	// --- reading -----------------------------------------------------------------------

	/**
	 * Parses one JSON object.
	 * @param text the document
	 * @return the object's members
	 * @throws EtcdException if the text is not a single JSON object
	 */
	static Map<String, Object> parseObject(String text) {
		Json json = new Json(text);
		json.skipWhitespace();
		Object value = json.readValue();
		json.skipWhitespace();
		if (json.index != text.length()) {
			throw new EtcdException("Trailing content after the JSON document at index " + json.index);
		}
		if (!(value instanceof Map<?, ?>)) {
			throw new EtcdException("Expected a JSON object but got " + value);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> members = (Map<String, Object>) value;
		return members;
	}

	/**
	 * Returns a member as an object.
	 * @param value the member, possibly absent
	 * @return its members, or {@code null} if the member is absent or null
	 */
	static @Nullable Map<String, Object> object(@Nullable Object value) {
		if (!(value instanceof Map<?, ?>)) {
			return null;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> members = (Map<String, Object>) value;
		return members;
	}

	/**
	 * Returns a member as an array.
	 * @param value the member, possibly absent
	 * @return its elements, or an empty list if the member is absent or null
	 */
	static List<Object> array(@Nullable Object value) {
		if (!(value instanceof List<?> elements)) {
			return List.of();
		}
		@SuppressWarnings("unchecked")
		List<Object> typed = (List<Object>) elements;
		return typed;
	}

	/**
	 * Returns a member as text.
	 * @param value the member, possibly absent
	 * @return the string, or {@code null} if the member is absent or null
	 */
	static @Nullable String text(@Nullable Object value) {
		return (value instanceof String string) ? string : null;
	}

	/**
	 * Returns a 64-bit member, written by etcd as a string but as a number when it is not
	 * an {@code int64}.
	 * @param value the member, possibly absent
	 * @param fallback what an absent member means, which for a proto3 field is its
	 * default
	 * @return the number
	 */
	static long integer(@Nullable Object value, long fallback) {
		return switch (value) {
			case String string -> Long.parseLong(string);
			case Double number -> number.longValue();
			case null, default -> fallback;
		};
	}

	/**
	 * Returns a boolean member.
	 * @param value the member, possibly absent
	 * @return the flag, {@code false} if the member is absent
	 */
	static boolean flag(@Nullable Object value) {
		return value instanceof Boolean flag && flag;
	}

	/**
	 * Returns a member holding bytes, which etcd renders as base64.
	 * @param value the member, possibly absent
	 * @return the decoded bytes, or {@code null} if the member is absent or null
	 */
	static byte @Nullable [] bytes(@Nullable Object value) {
		String encoded = text(value);
		return (encoded == null) ? null : Base64.getDecoder().decode(encoded);
	}

	private Object readValue() {
		char c = peek();
		return switch (c) {
			case '{' -> readObject();
			case '[' -> readArray();
			case '"' -> readString();
			case 't' -> readLiteral("true", Boolean.TRUE);
			case 'f' -> readLiteral("false", Boolean.FALSE);
			case 'n' -> readLiteral("null", NULL);
			default -> readNumber();
		};
	}

	private Map<String, Object> readObject() {
		expect('{');
		Map<String, Object> members = new LinkedHashMap<>();
		skipWhitespace();
		if (peek() == '}') {
			this.index++;
			return members;
		}
		while (true) {
			skipWhitespace();
			String name = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			members.put(name, readValue());
			skipWhitespace();
			char c = next();
			if (c == '}') {
				return members;
			}
			if (c != ',') {
				throw error("Expected ',' or '}' in an object but got '" + c + "'");
			}
		}
	}

	private List<Object> readArray() {
		expect('[');
		List<Object> elements = new ArrayList<>();
		skipWhitespace();
		if (peek() == ']') {
			this.index++;
			return elements;
		}
		while (true) {
			skipWhitespace();
			elements.add(readValue());
			skipWhitespace();
			char c = next();
			if (c == ']') {
				return elements;
			}
			if (c != ',') {
				throw error("Expected ',' or ']' in an array but got '" + c + "'");
			}
		}
	}

	private String readString() {
		expect('"');
		StringBuilder text = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return text.toString();
			}
			if (c != '\\') {
				text.append(c);
				continue;
			}
			char escaped = next();
			switch (escaped) {
				case '"', '\\', '/' -> text.append(escaped);
				case 'b' -> text.append('\b');
				case 'f' -> text.append('\f');
				case 'n' -> text.append('\n');
				case 'r' -> text.append('\r');
				case 't' -> text.append('\t');
				case 'u' -> {
					if (this.index + 4 > this.text.length()) {
						throw error("Truncated unicode escape");
					}
					text.append((char) Integer.parseInt(this.text, this.index, this.index + 4, 16));
					this.index += 4;
				}
				default -> throw error("Unknown escape '\\" + escaped + "'");
			}
		}
	}

	private Object readLiteral(String literal, Object value) {
		if (!this.text.startsWith(literal, this.index)) {
			throw error("Expected " + literal);
		}
		this.index += literal.length();
		return value;
	}

	private Double readNumber() {
		int start = this.index;
		while (this.index < this.text.length() && "+-.eE0123456789".indexOf(this.text.charAt(this.index)) >= 0) {
			this.index++;
		}
		if (start == this.index) {
			throw error("Expected a value but got '" + this.text.charAt(start) + "'");
		}
		return Double.valueOf(this.text.substring(start, this.index));
	}

	private void skipWhitespace() {
		while (this.index < this.text.length() && Character.isWhitespace(this.text.charAt(this.index))) {
			this.index++;
		}
	}

	private char peek() {
		if (this.index >= this.text.length()) {
			throw error("Unexpected end of the JSON document");
		}
		return this.text.charAt(this.index);
	}

	private char next() {
		char c = peek();
		this.index++;
		return c;
	}

	private void expect(char expected) {
		char c = next();
		if (c != expected) {
			throw error("Expected '" + expected + "' but got '" + c + "'");
		}
	}

	private EtcdException error(String message) {
		return new EtcdException(message + " at index " + this.index);
	}

	// --- writing -----------------------------------------------------------------------

	/**
	 * Returns a builder for a JSON object.
	 * @return a new, empty object
	 */
	static Writer write() {
		return new Writer();
	}

	/**
	 * Builds one request body.
	 *
	 * <p>
	 * Only the shapes etcd's gateway is sent are buildable: members holding bytes
	 * (base64), 64-bit numbers (strings, as the gateway expects them), flags, enum names
	 * and nested objects and arrays. A finished builder renders itself with
	 * {@link #toString()}.
	 */
	static final class Writer {

		private final StringBuilder json = new StringBuilder("{");

		private Writer() {
		}

		/**
		 * Adds a member holding bytes, which the gateway takes as base64.
		 * @param name the member name
		 * @param value the bytes
		 * @return this writer
		 */
		Writer bytes(String name, byte[] value) {
			return raw(name, "\"" + Base64.getEncoder().encodeToString(value) + "\"");
		}

		/**
		 * Adds a 64-bit member, which the gateway takes as a string.
		 * @param name the member name
		 * @param value the number
		 * @return this writer
		 */
		Writer integer(String name, long value) {
			return raw(name, "\"" + value + "\"");
		}

		/**
		 * Adds a flag.
		 * @param name the member name
		 * @param value the flag
		 * @return this writer
		 */
		Writer flag(String name, boolean value) {
			return raw(name, String.valueOf(value));
		}

		/**
		 * Adds a member holding an enum constant or any other plain string.
		 * @param name the member name
		 * @param value the text, escaped
		 * @return this writer
		 */
		Writer text(String name, String value) {
			return raw(name, quote(value));
		}

		/**
		 * Adds a nested object.
		 * @param name the member name
		 * @param value the nested object
		 * @return this writer
		 */
		Writer object(String name, Writer value) {
			return raw(name, value.toString());
		}

		/**
		 * Adds an array of objects.
		 * @param name the member name
		 * @param values the elements, in order
		 * @return this writer
		 */
		Writer array(String name, List<Writer> values) {
			StringBuilder elements = new StringBuilder("[");
			for (Writer value : values) {
				if (elements.length() > 1) {
					elements.append(',');
				}
				elements.append(value);
			}
			return raw(name, elements.append(']').toString());
		}

		private Writer raw(String name, String rendered) {
			if (this.json.length() > 1) {
				this.json.append(',');
			}
			this.json.append(quote(name)).append(':').append(rendered);
			return this;
		}

		private static String quote(String value) {
			StringBuilder quoted = new StringBuilder("\"");
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '"' -> quoted.append("\\\"");
					case '\\' -> quoted.append("\\\\");
					case '\b' -> quoted.append("\\b");
					case '\f' -> quoted.append("\\f");
					case '\n' -> quoted.append("\\n");
					case '\r' -> quoted.append("\\r");
					case '\t' -> quoted.append("\\t");
					default -> {
						if (c < 0x20) {
							quoted.append(String.format("\\u%04x", (int) c));
						}
						else {
							quoted.append(c);
						}
					}
				}
			}
			return quoted.append('"').toString();
		}

		@Override
		public String toString() {
			return this.json + "}";
		}

	}

}
