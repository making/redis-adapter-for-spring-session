package am.ik.redis.adapter.pubsub;

/**
 * Redis glob matching, as {@code PSUBSCRIBE} uses it to decide which channels a pattern
 * covers.
 *
 * <p>
 * The syntax is the one Redis implements: {@code *} matches any run of bytes including
 * none, {@code ?} matches exactly one byte, {@code [...]} matches one byte out of a set
 * (which may contain {@code a-z} ranges and may be negated with a leading {@code ^}), and
 * a backslash escapes the byte that follows so a literal {@code *} can be matched. Every
 * other byte matches itself.
 *
 * <p>
 * Matching is done on <strong>bytes</strong>, never on decoded text: channel names come
 * off the wire as bytes and a pattern must not depend on a charset to decide what it
 * covers. Ranges therefore compare unsigned byte values.
 *
 * <p>
 * Spring Session only needs a trailing {@code *} (its created-event pattern is
 * {@code spring:session:event:<db>:created:*}), but implementing the whole syntax costs
 * little and means a client that subscribes with any other Redis pattern is not silently
 * given the wrong messages.
 */
public final class GlobPattern {

	private GlobPattern() {
	}

	/**
	 * Reports whether {@code value} matches {@code pattern}.
	 * @param pattern the glob pattern bytes
	 * @param value the bytes to test, typically a channel name
	 * @return {@code true} if the pattern covers the value
	 */
	public static boolean matches(byte[] pattern, byte[] value) {
		return matches(pattern, 0, value, 0);
	}

	/**
	 * Matches the pattern from {@code patternIndex} against the value from
	 * {@code valueIndex}.
	 *
	 * <p>
	 * A {@code *} is the only construct that needs to look ahead: it tries every split
	 * point in the remaining value, which is what makes the match backtrack. Every other
	 * construct consumes exactly one byte of the value, so the loop advances both cursors
	 * and never has to reconsider.
	 */
	private static boolean matches(byte[] pattern, int patternIndex, byte[] value, int valueIndex) {
		int p = patternIndex;
		int v = valueIndex;
		while (p < pattern.length && v < value.length) {
			if (pattern[p] == '*') {
				// A run of stars covers exactly what one does.
				while (p + 1 < pattern.length && pattern[p + 1] == '*') {
					p++;
				}
				if (p + 1 == pattern.length) {
					return true;
				}
				for (int split = v; split <= value.length; split++) {
					if (matches(pattern, p + 1, value, split)) {
						return true;
					}
				}
				return false;
			}
			if (pattern[p] == '?') {
				p++;
				v++;
				continue;
			}
			if (pattern[p] == '[') {
				CharacterClass characterClass = matchCharacterClass(pattern, p, value[v]);
				if (!characterClass.matched()) {
					return false;
				}
				p = characterClass.nextIndex();
				v++;
				continue;
			}
			// A backslash makes the next byte literal, including a star or a bracket.
			if (pattern[p] == '\\' && p + 1 < pattern.length) {
				p++;
			}
			if (pattern[p] != value[v]) {
				return false;
			}
			p++;
			v++;
		}
		if (v == value.length) {
			// Trailing stars are allowed to match nothing at all.
			while (p < pattern.length && pattern[p] == '*') {
				p++;
			}
		}
		return p == pattern.length && v == value.length;
	}

	/**
	 * The outcome of matching one {@code [...]} group: whether the byte was covered, and
	 * where the pattern continues.
	 *
	 * @param nextIndex the index just past the closing bracket
	 * @param matched whether the byte is a member of the group
	 */
	private record CharacterClass(int nextIndex, boolean matched) {
	}

	/**
	 * Matches a single byte against the {@code [...]} group starting at
	 * {@code openingIndex}. An unterminated group ends at the end of the pattern, which
	 * is how Redis treats it too.
	 */
	private static CharacterClass matchCharacterClass(byte[] pattern, int openingIndex, byte value) {
		int index = openingIndex + 1;
		boolean negated = index < pattern.length && pattern[index] == '^';
		if (negated) {
			index++;
		}
		boolean matched = false;
		while (index < pattern.length && pattern[index] != ']') {
			if (pattern[index] == '\\' && index + 1 < pattern.length) {
				index++;
				matched |= pattern[index] == value;
			}
			else if (index + 2 < pattern.length && pattern[index + 1] == '-' && pattern[index + 2] != ']') {
				matched |= inRange(pattern[index], pattern[index + 2], value);
				index += 2;
			}
			else {
				matched |= pattern[index] == value;
			}
			index++;
		}
		int nextIndex = (index < pattern.length) ? index + 1 : index;
		return new CharacterClass(nextIndex, negated != matched);
	}

	/**
	 * Reports whether {@code value} falls inside the inclusive range, comparing unsigned
	 * byte values. A range written backwards is read as if it had been written the right
	 * way round, as Redis reads it.
	 */
	private static boolean inRange(byte from, byte to, byte value) {
		int low = from & 0xFF;
		int high = to & 0xFF;
		if (low > high) {
			int swapped = low;
			low = high;
			high = swapped;
		}
		int candidate = value & 0xFF;
		return candidate >= low && candidate <= high;
	}

}
