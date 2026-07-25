package am.ik.redis.adapter.etcd;

import am.ik.redis.adapter.store.RedisValue;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link Mutation} decided.
 *
 * @param <T> what the operation returns
 * @param result what the operation returns to its caller
 * @param write the value to store, or {@code null} to store nothing
 * @param vanish whether the key should go without being announced, which is what an
 * emptied set does
 */
record Outcome<T>(T result, @Nullable RedisValue write, boolean vanish) {

	static <T> Outcome<T> write(T result, RedisValue value) {
		return new Outcome<>(result, value, false);
	}

	static <T> Outcome<T> nothing(T result) {
		return new Outcome<>(result, null, false);
	}

	static <T> Outcome<T> vanish(T result) {
		return new Outcome<>(result, null, true);
	}
}
