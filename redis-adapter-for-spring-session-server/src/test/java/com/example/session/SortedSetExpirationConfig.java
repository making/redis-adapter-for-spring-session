package com.example.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisSessionExpirationStore;
import org.springframework.session.data.redis.SortedSetRedisSessionExpirationStore;

// Opts an indexed application into the alternative expiration store, the one that keeps
// every live session in a single sorted set instead of in a set per minute. Spring Session
// picks up a RedisSessionExpirationStore bean and hands it to the session repository, which
// from then on records expirations with ZADD, drops them with ZREM and asks
// ZREVRANGEBYSCORE which sessions are due.
//
// The store writes through a template of its own rather than through the repository's,
// which would be a cycle - the repository is built from this bean. Its serializers are set
// up the way Spring Session sets up its own: keys and hash fields are text, and everything
// else, a session id in the sorted set included, is a serialized blob.
//
// The region between the markers below is quoted verbatim in README.md.
// tag::sorted-set-expiration-config[]
@Configuration
public class SortedSetExpirationConfig {

	@Bean
	public RedisSessionExpirationStore sortedSetRedisSessionExpirationStore(RedisConnectionFactory connectionFactory) {
		RedisTemplate<String, Object> redisOperations = new RedisTemplate<>();
		redisOperations.setKeySerializer(RedisSerializer.string());
		redisOperations.setHashKeySerializer(RedisSerializer.string());
		redisOperations.setConnectionFactory(connectionFactory);
		redisOperations.afterPropertiesSet();
		return new SortedSetRedisSessionExpirationStore(redisOperations,
				RedisIndexedSessionRepository.DEFAULT_NAMESPACE);
	}

}
// end::sorted-set-expiration-config[]
