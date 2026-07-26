package am.ik.redis.adapter.boot.inmemory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the in-memory backend can be tuned with. It is read by this server and no other: a
 * server built around another backend brings its own properties, under its own
 * {@code redis-adapter.<backend>} prefix, and has never heard of these.
 *
 * <p>
 * Both settings are about <em>active</em> expiry: the sweep that removes keys whose time
 * has passed but which nobody has come back to. Keys that are touched expire on that
 * access whatever these are set to, so turning the sweeper off does not keep a dead
 * session readable — it only delays the {@code expired} notification until something asks
 * for the key.
 *
 * @param sweeperEnabled whether to run the background sweep at all
 * @param sweepInterval how long to wait between sweeps, which is the longest an expired
 * key can sit there unnoticed
 */
@ConfigurationProperties(prefix = "redis-adapter.in-memory")
public record InMemoryBackendProperties(@DefaultValue("true") boolean sweeperEnabled,
		@DefaultValue("1s") Duration sweepInterval) {

	public InMemoryBackendProperties {
		if (sweepInterval.isNegative() || sweepInterval.isZero()) {
			throw new IllegalArgumentException(
					"redis-adapter.in-memory.sweep-interval must be positive: " + sweepInterval);
		}
	}

}
