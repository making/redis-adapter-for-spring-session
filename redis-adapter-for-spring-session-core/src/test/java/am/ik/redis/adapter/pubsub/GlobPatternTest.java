package am.ik.redis.adapter.pubsub;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobPatternTest {

	/** The one pattern Spring Session subscribes to, and the channel it must match. */
	@Test
	void aTrailingStarMatchesTheSessionIdOfACreatedEvent() {
		assertThat(matches("spring:session:event:0:created:*", "spring:session:event:0:created:abc")).isTrue();
	}

	@Test
	void aTrailingStarDoesNotMatchAnotherEvent() {
		assertThat(matches("spring:session:event:0:created:*", "spring:session:event:0:other:abc")).isFalse();
	}

	@Test
	void aStarMatchesNothingAsWellAsSomething() {
		assertThat(matches("channel:*", "channel:")).isTrue();
	}

	@Test
	void aStarMatchesSeparatorsToo() {
		assertThat(matches("spring:*", "spring:session:event:0:created:abc")).isTrue();
	}

	@Test
	void aLoneStarMatchesEverything() {
		assertThat(matches("*", "")).isTrue();
		assertThat(matches("*", "__keyevent@0__:expired")).isTrue();
	}

	@Test
	void aPatternWithoutWildcardsMatchesOnlyItself() {
		assertThat(matches("__keyevent@0__:del", "__keyevent@0__:del")).isTrue();
		assertThat(matches("__keyevent@0__:del", "__keyevent@0__:expired")).isFalse();
		assertThat(matches("__keyevent@0__:del", "__keyevent@0__:de")).isFalse();
	}

	@Test
	void severalStarsBehaveAsOne() {
		assertThat(matches("a**b", "ab")).isTrue();
		assertThat(matches("a*b*c", "a-b-c")).isTrue();
		assertThat(matches("a*b*c", "a-b-d")).isFalse();
	}

	@Test
	void aQuestionMarkMatchesExactlyOneByte() {
		assertThat(matches("event:?", "event:1")).isTrue();
		assertThat(matches("event:?", "event:")).isFalse();
		assertThat(matches("event:?", "event:12")).isFalse();
	}

	@Test
	void aCharacterClassMatchesAnyOfItsMembers() {
		assertThat(matches("event:[abc]", "event:b")).isTrue();
		assertThat(matches("event:[abc]", "event:d")).isFalse();
	}

	@Test
	void aCharacterClassSupportsRanges() {
		assertThat(matches("event:[a-z]", "event:q")).isTrue();
		assertThat(matches("event:[a-z]", "event:Q")).isFalse();
	}

	@Test
	void aCharacterClassCanBeNegated() {
		assertThat(matches("event:[^a]", "event:b")).isTrue();
		assertThat(matches("event:[^a]", "event:a")).isFalse();
	}

	@Test
	void abackslashEscapesTheNextByte() {
		assertThat(matches("event\\:*", "event:abc")).isTrue();
		assertThat(matches("literal\\*", "literal*")).isTrue();
		assertThat(matches("literal\\*", "literally")).isFalse();
	}

	@Test
	void patternsAreMatchedByteWiseSoTheyNeverAssumeText() {
		byte[] pattern = { 'a', '*' };
		byte[] value = { 'a', (byte) 0xFF, (byte) 0x00 };

		assertThat(GlobPattern.matches(pattern, value)).isTrue();
	}

	private static boolean matches(String pattern, String value) {
		return GlobPattern.matches(pattern.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
	}

}
