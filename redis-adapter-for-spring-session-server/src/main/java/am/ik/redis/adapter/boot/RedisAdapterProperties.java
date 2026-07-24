package am.ik.redis.adapter.boot;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything an operator sets on the adapter server itself. Backends have properties of
 * their own, under {@code redis-adapter.<backend>}.
 *
 * <p>
 * The defaults are those of the Redis the adapter stands in for, so an application that
 * was pointed at a stock Redis reaches the adapter by changing nothing but the host: port
 * 6379, one database, no password.
 *
 * <p>
 * Every property can be set the usual Spring Boot ways, environment variables included
 * ({@code REDIS_ADAPTER_PORT}, {@code REDIS_ADAPTER_PASSWORD}, and so on).
 *
 * @param bindAddress the address to listen on; the default accepts on every interface,
 * which is what a server in a container wants
 * @param port the port to listen on, {@code 0} for an ephemeral one
 * @param backend which backend holds the session data; the bundled
 * {@value #IN_MEMORY_BACKEND} store is single-node, so a horizontally scaled deployment
 * needs a shared external one
 * @param password the password clients must authenticate with, or {@code null} to let
 * anything that reaches the port read and write every session
 * @param databases how many numbered databases to serve, each an independent keyspace
 * @param shutdownTimeout how long a connection still running a command is given before it
 * is interrupted on shutdown
 * @param ssl the transport security settings
 */
@ConfigurationProperties(prefix = "redis-adapter")
public record RedisAdapterProperties(@DefaultValue("0.0.0.0") String bindAddress, @DefaultValue("6379") int port,
		@DefaultValue(RedisAdapterProperties.IN_MEMORY_BACKEND) String backend, @Nullable String password,
		@DefaultValue("1") int databases, @DefaultValue("10s") Duration shutdownTimeout, @DefaultValue Ssl ssl) {

	/** The name of the bundled in-memory backend, and the default. */
	public static final String IN_MEMORY_BACKEND = "in-memory";

	public RedisAdapterProperties {
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("redis-adapter.port must be between 0 and 65535: " + port);
		}
		if (databases < 1) {
			throw new IllegalArgumentException("redis-adapter.databases must be at least 1: " + databases);
		}
		if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
			throw new IllegalArgumentException("redis-adapter.shutdown-timeout must be positive: " + shutdownTimeout);
		}
	}

	/**
	 * Transport security for the adapter's port. Clients then connect over
	 * {@code rediss://}.
	 *
	 * <p>
	 * Naming a bundle is all it takes: {@code enabled} is unset by default and then
	 * follows the bundle. It exists so that an operator can keep a bundle configured and
	 * still fall back to plain TCP, by writing {@code false} and meaning it. What it
	 * deliberately cannot do is leave a server plain because a property was forgotten —
	 * the one failure nobody notices, since a plain port answers every client that asks.
	 *
	 * @param enabled whether to serve TLS rather than plain TCP; unset means TLS exactly
	 * when a bundle is named
	 * @param bundle the name of the {@code spring.ssl.bundle.*} holding the certificate
	 * and key the server identifies itself with, and, for {@link ClientAuth client
	 * authentication}, the certificates it verifies clients against
	 * @param clientAuth whether clients have to identify themselves with a certificate of
	 * their own
	 */
	public record Ssl(@Nullable Boolean enabled, @Nullable String bundle, @DefaultValue("none") ClientAuth clientAuth) {

		public Ssl {
			if (Boolean.TRUE.equals(enabled) && bundle == null) {
				// Starting plain here would serve every client in clear text under a
				// setting that says otherwise.
				throw new IllegalArgumentException(
						"redis-adapter.ssl.enabled is true but redis-adapter.ssl.bundle names no SSL bundle");
			}
		}

		/**
		 * Reports whether the port is served over TLS: when it is, {@link #bundle()}
		 * names the bundle it is served from.
		 * @return {@code true} if the server should terminate TLS
		 */
		public boolean isEnabled() {
			Boolean enabled = this.enabled;
			return (enabled != null) ? enabled : (this.bundle != null);
		}

		/**
		 * What the server asks of a client's own certificate, mirroring the SSL socket
		 * settings the JDK offers.
		 */
		public enum ClientAuth {

			/** Clients are not asked for a certificate. */
			NONE,

			/**
			 * Clients are asked for a certificate but are served without one, which is
			 * what makes it usable while clients are still being issued certificates.
			 */
			WANT,

			/**
			 * Clients that present no certificate the bundle's trust store accepts are
			 * refused: the certificate is the credential.
			 */
			NEED

		}

	}

}
