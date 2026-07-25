package am.ik.redis.adapter.etcd;

import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import org.jspecify.annotations.Nullable;

/**
 * What one operation does to whatever is under a key.
 *
 * <p>
 * A mutation is a pure function of the value it is handed, and says nothing about when or
 * against what it runs. That is what lets the store apply it again when the key changed
 * underneath it — and, the reason it matters more, apply a whole queue of them to one
 * value that was read once, which is what {@link KeyQueues} does with them.
 *
 * @param <T> what the operation returns
 */
@FunctionalInterface
interface Mutation<T> {

	/**
	 * Decides what should replace the current value.
	 * @param current the value under the key, or {@code null} if it is absent
	 * @return what to write and what to return
	 * @throws TypeMismatchException if the key holds another type
	 */
	Outcome<T> apply(@Nullable RedisValue current);

}
