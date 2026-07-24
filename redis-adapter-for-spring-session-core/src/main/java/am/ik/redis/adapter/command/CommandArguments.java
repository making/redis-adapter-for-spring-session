package am.ik.redis.adapter.command;

import java.nio.charset.StandardCharsets;

/**
 * Helpers for reading raw command arguments.
 *
 * <p>
 * Command names, subcommands and key names are text on the wire; payloads are opaque
 * bytes and never go through here.
 */
final class CommandArguments {

	/** Longest run of untrusted bytes echoed back inside an error message. */
	private static final int MAX_DISPLAY_LENGTH = 128;

	private CommandArguments() {
	}

	/**
	 * Decodes an argument as UTF-8 text.
	 * @param value the argument bytes
	 * @return the decoded text
	 */
	static String text(byte[] value) {
		return new String(value, StandardCharsets.UTF_8);
	}

	/**
	 * Upper-cases an argument for case-insensitive matching of command and subcommand
	 * names. Only ASCII letters are folded, and every other byte is preserved as its
	 * Latin-1 character, so distinct byte sequences never collide.
	 * @param value the argument bytes
	 * @return the upper-cased name
	 */
	static String upperCase(byte[] value) {
		char[] name = new char[value.length];
		for (int i = 0; i < value.length; i++) {
			int c = value[i] & 0xFF;
			name[i] = (char) ((c >= 'a' && c <= 'z') ? (c - ('a' - 'A')) : c);
		}
		return new String(name);
	}

	/**
	 * Parses an argument as a decimal integer.
	 * @param value the argument bytes
	 * @return the parsed value
	 * @throws RedisCommandException if the argument is not an integer
	 */
	static long integer(byte[] value) {
		try {
			return Long.parseLong(text(value));
		}
		catch (NumberFormatException e) {
			throw RedisCommandException.notAnInteger();
		}
	}

	/**
	 * Renders untrusted bytes for inclusion in an error message: non-printable bytes
	 * become {@code .} and long values are truncated. Error replies are line-framed, so
	 * raw client bytes must never reach them unfiltered.
	 * @param value the argument bytes
	 * @return a safe single-line rendering
	 */
	static String display(byte[] value) {
		int length = Math.min(value.length, MAX_DISPLAY_LENGTH);
		StringBuilder display = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			int c = value[i] & 0xFF;
			display.append((c >= 0x20 && c <= 0x7E) ? (char) c : '.');
		}
		if (value.length > length) {
			display.append("...");
		}
		return display.toString();
	}

}
