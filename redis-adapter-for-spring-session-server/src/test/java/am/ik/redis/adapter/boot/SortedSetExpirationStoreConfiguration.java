package am.ik.redis.adapter.boot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisSessionExpirationStore;
import org.springframework.session.data.redis.SortedSetRedisSessionExpirationStore;

/**
 * Opts an indexed application into the alternative expiration store, the one that keeps
 * every live session in a single sorted set instead of in a set per minute.
 *
 * <p>
 * This is all an application has to do: Spring Session picks up a
 * {@link RedisSessionExpirationStore} bean and hands it to the session repository, which
 * from then on records expirations with {@code ZADD}, drops them with {@code ZREM} and
 * asks {@code ZREVRANGEBYSCORE} which sessions are due.
 *
 * <p>
 * The store writes through a template of its own rather than through the repository's,
 * which would be a cycle — the repository is built from this bean. Its serializers are
 * set up the way Spring Session sets up its own: keys and hash fields are text, and
 * everything else, a session id in the sorted set included, is a serialized blob.
 */
@TestConfiguration(proxyBeanMethods = false)
class SortedSetExpirationStoreConfiguration {

	@Bean
	RedisSessionExpirationStore sortedSetRedisSessionExpirationStore(RedisConnectionFactory connectionFactory) {
		RedisTemplate<String, Object> redisOperations = new RedisTemplate<>();
		redisOperations.setKeySerializer(RedisSerializer.string());
		redisOperations.setHashKeySerializer(RedisSerializer.string());
		redisOperations.setConnectionFactory(connectionFactory);
		redisOperations.afterPropertiesSet();
		return new SortedSetRedisSessionExpirationStore(redisOperations,
				RedisIndexedSessionRepository.DEFAULT_NAMESPACE);
	}

}
