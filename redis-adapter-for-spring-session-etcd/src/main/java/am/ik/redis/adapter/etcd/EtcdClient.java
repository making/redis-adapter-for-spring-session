package am.ik.redis.adapter.etcd;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The etcd v3 API, over its gRPC gateway.
 *
 * <p>
 * etcd serves its whole v3 API as JSON over HTTP on the same client port as gRPC (the
 * {@code --enable-grpc-gateway} it is built and shipped with by default), which is what
 * lets this backend talk to a real etcd cluster with nothing but the JDK's HTTP client —
 * no gRPC stack, no protobuf, no Netty, and therefore the same runtime dependencies as
 * the bundled in-memory backend. The gateway is a stable, documented part of etcd, and
 * the requests here are the ones a client library would send over gRPC.
 *
 * <p>
 * Every operation is one round trip, and every operation that reads and then writes is a
 * single etcd transaction guarded by the revision the read returned, so two adapter
 * replicas racing on one key cannot lose an update. The methods are the ones
 * {@link EtcdKeyValueStore} needs and nothing more general: what is atomic is what the
 * store needs to be atomic.
 *
 * <h2>Endpoints</h2> A cluster is given as several endpoints. Requests go to the one that
 * last worked and move on to the next when it cannot be reached, so a member going away
 * costs one failed request rather than the store. Only transport failures move the cursor
 * — an answer from etcd, even a refusal, means the endpoint is serving.
 *
 * <h2>Authentication</h2> When a user is configured, the client authenticates on first
 * use and sends the token it is given on every request. A token that etcd stops accepting
 * (it has a lifetime, and a restarted cluster forgets it) is discarded and the request is
 * retried once with a fresh one, so the store never fails because a token aged out.
 */
final class EtcdClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(EtcdClient.class);

	/** Lease id 0 means "no lease" in etcd, so a key attached to it never expires. */
	static final long NO_LEASE = 0L;

	private final HttpClient http;

	private final List<URI> endpoints;

	private final Duration requestTimeout;

	private final @Nullable String username;

	private final @Nullable String password;

	private final AtomicInteger endpoint = new AtomicInteger();

	private volatile @Nullable String token;

	private EtcdClient(Builder builder) {
		this.endpoints = builder.endpoints.stream().map(EtcdClient::normalize).toList();
		if (this.endpoints.isEmpty()) {
			throw new IllegalArgumentException("at least one etcd endpoint is required");
		}
		this.requestTimeout = builder.requestTimeout;
		this.username = builder.username;
		this.password = builder.password;
		HttpClient.Builder http = HttpClient.newBuilder()
			// The gateway shares its port with gRPC, and HTTP/1.1 is what every etcd
			// serves there whether or not the port also speaks h2.
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(builder.connectTimeout);
		SSLContext sslContext = builder.sslContext;
		if (sslContext != null) {
			http.sslContext(sslContext);
		}
		this.http = http.build();
	}

	/**
	 * Returns a builder.
	 * @return a new builder
	 */
	static Builder builder() {
		return new Builder();
	}

	private static URI normalize(String endpoint) {
		String uri = endpoint.trim();
		if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
			// etcd itself is usually configured with bare host:port or an etcd:// URL.
			uri = "http://" + uri;
		}
		while (uri.endsWith("/")) {
			uri = uri.substring(0, uri.length() - 1);
		}
		return URI.create(uri);
	}

	// --- keys --------------------------------------------------------------------------

	/**
	 * Reads one key.
	 * @param key the etcd key
	 * @return what is stored, or {@code null} if the key does not exist
	 */
	@Nullable Kv get(byte[] key) {
		Map<String, Object> response = call("/v3/kv/range", Json.write().bytes("key", key).toString());
		return firstKv(Json.array(response.get("kvs")));
	}

	/**
	 * Writes a key if it has not changed since it was read, and reads it again if it has.
	 *
	 * <p>
	 * Returning the current value from the same transaction that refused the write is
	 * what makes a contended key workable: a caller that loses has what it needs to try
	 * again without a round trip of its own, which halves both the retry's cost and the
	 * window in which it can lose again. Under load that window is the whole problem —
	 * several sessions expiring in the same minute all add themselves to one set.
	 * @param key the etcd key
	 * @param expectedModRevision the revision the key was last modified at, {@code 0} if
	 * it did not exist
	 * @param value the value to write
	 * @param lease the lease to attach the key to, or {@link #NO_LEASE}
	 * @return the write, which either happened or carries what is under the key now
	 */
	Write putIfUnchanged(byte[] key, long expectedModRevision, byte[] value, long lease) {
		Json.Writer put = Json.write().bytes("key", key).bytes("value", value);
		if (lease != NO_LEASE) {
			put.integer("lease", lease);
		}
		Transaction transaction = transaction(unchanged(key, expectedModRevision),
				List.of(Json.write().object("request_put", put)), List.of(range(key)));
		if (transaction.succeeded()) {
			return new Write(transaction.revision(), null);
		}
		return new Write(0L, transaction.firstKv());
	}

	/**
	 * Removes a key if it has not changed since it was read.
	 * @param key the etcd key
	 * @param expectedModRevision the revision the key was last modified at
	 * @return {@code true} if the key was removed
	 */
	boolean deleteIfUnchanged(byte[] key, long expectedModRevision) {
		return transaction(unchanged(key, expectedModRevision),
				List.of(Json.write().object("request_delete_range", Json.write().bytes("key", key))), List.of())
			.succeeded();
	}

	/**
	 * The result of a guarded write.
	 *
	 * @param revision the revision the value was written at, or {@code 0} if it was not
	 * written
	 * @param current what is under the key now, when the write did not happen;
	 * {@code null} means the key is absent — which is also what a lease that died under
	 * it leaves behind
	 */
	record Write(long revision, @Nullable Kv current) {

		/**
		 * Reports whether the value was written.
		 * @return {@code true} if the write happened
		 */
		boolean written() {
			return this.revision != 0;
		}
	}

	/**
	 * Removes a key and returns what it held, in one operation.
	 * @param key the etcd key
	 * @return what the key held, or {@code null} if it did not exist
	 */
	@Nullable Kv deleteAndReturnPrevious(byte[] key) {
		Map<String, Object> response = call("/v3/kv/deleterange",
				Json.write().bytes("key", key).flag("prev_kv", true).toString());
		return firstKv(Json.array(response.get("prev_kvs")));
	}

	/**
	 * Moves a value to another key, in one transaction, leaving a
	 * {@link Envelope#tombstone() tombstone} behind so that the removal of the source
	 * announces nothing.
	 * @param source the etcd key the value is moving from
	 * @param expectedModRevision the revision the source was last modified at
	 * @param tombstone the encoded tombstone to leave at the source
	 * @param tombstoneLease the short lease the tombstone is attached to, so it goes even
	 * if this process dies before removing it
	 * @param destination the etcd key the value is moving to
	 * @param value the encoded value being moved
	 * @param lease the lease the value is attached to, which moves with it, or
	 * {@link #NO_LEASE}
	 * @return the revision the move happened at, or {@code 0} if the source changed in
	 * the meantime
	 */
	long moveIfUnchanged(byte[] source, long expectedModRevision, byte[] tombstone, long tombstoneLease,
			byte[] destination, byte[] value, long lease) {
		Json.Writer put = Json.write().bytes("key", destination).bytes("value", value);
		if (lease != NO_LEASE) {
			put.integer("lease", lease);
		}
		Transaction transaction = transaction(unchanged(source, expectedModRevision), List.of(Json.write()
			.object("request_put",
					Json.write().bytes("key", source).bytes("value", tombstone).integer("lease", tombstoneLease)),
				Json.write().object("request_put", put)), List.of());
		return transaction.succeeded() ? transaction.revision() : 0L;
	}

	private static Json.Writer unchanged(byte[] key, long expectedModRevision) {
		// Comparing the modification revision to 0 is how etcd is asked whether a key is
		// still absent, so one comparison covers both "unchanged" and "still not there".
		return Json.write()
			.bytes("key", key)
			.text("target", "MOD")
			.text("result", "EQUAL")
			.integer("mod_revision", expectedModRevision);
	}

	private static Json.Writer range(byte[] key) {
		return Json.write().object("request_range", Json.write().bytes("key", key));
	}

	private Transaction transaction(Json.Writer comparison, List<Json.Writer> operations, List<Json.Writer> otherwise) {
		Json.Writer body = Json.write().array("compare", List.of(comparison)).array("success", operations);
		if (!otherwise.isEmpty()) {
			body.array("failure", otherwise);
		}
		Map<String, Object> response;
		try {
			response = call("/v3/kv/txn", body.toString());
		}
		catch (EtcdException e) {
			if (isLeaseGone(e)) {
				// The lease the key was attached to died between the read and this write,
				// so
				// the key is already gone. That is the same answer as "it changed" — read
				// it
				// again — and it is why a caller must never treat a refusal as final.
				logger.debug("etcd refused a transaction because a lease had gone: {}", e.getMessage());
				return new Transaction(false, 0L, List.of());
			}
			throw e;
		}
		long revision = Json.integer(header(response).get("revision"), 0L);
		return new Transaction(Json.flag(response.get("succeeded")), revision, Json.array(response.get("responses")));
	}

	private static boolean isLeaseGone(EtcdException e) {
		String message = e.getMessage();
		return message != null && message.contains("lease not found");
	}

	private record Transaction(boolean succeeded, long revision, List<Object> responses) {

		/**
		 * Returns the key-value pair the transaction's first read found.
		 * @return the pair, or {@code null} if the transaction read nothing or found
		 * nothing
		 */
		@Nullable Kv firstKv() {
			for (Object response : this.responses) {
				Map<String, Object> members = Json.object(response);
				Map<String, Object> read = (members != null) ? Json.object(members.get("response_range")) : null;
				if (read != null) {
					return EtcdClient.firstKv(Json.array(read.get("kvs")));
				}
			}
			return null;
		}
	}

	// --- leases ------------------------------------------------------------------------

	/**
	 * Grants a lease, which is how etcd expires a key nobody comes back to.
	 * @param ttlSeconds how long the lease lives; etcd treats this as a floor and may
	 * keep it a little longer, never less
	 * @return the lease id
	 */
	long grantLease(long ttlSeconds) {
		Map<String, Object> response = call("/v3/lease/grant", Json.write().integer("TTL", ttlSeconds).toString());
		long lease = Json.integer(response.get("ID"), NO_LEASE);
		if (lease == NO_LEASE) {
			throw new EtcdException("etcd granted no lease: " + response);
		}
		return lease;
	}

	/**
	 * Renews a lease, giving it the whole of the TTL it was granted with again.
	 *
	 * <p>
	 * This is the cheap way to push a deadline out, and the reason is that etcd renews a
	 * lease on the leader alone: no raft proposal is committed, where granting one and
	 * revoking another are two. It is what makes a session touched on every request cost
	 * one raft write instead of three.
	 *
	 * <p>
	 * A lease that etcd no longer has is not an error there — the answer simply carries
	 * no TTL — and it is not one here either: the key it held is gone with it, so the
	 * caller grants a new lease and finds out on its next read.
	 * @param lease the lease id
	 * @return {@code true} if the lease is alive and now has its full TTL again
	 */
	boolean keepAliveLease(long lease) {
		if (lease == NO_LEASE) {
			return false;
		}
		// The gateway renders this endpoint's stream as a "result" wrapper, exactly as it
		// does a watch's, because etcd's own API is a bidirectional stream: one request
		// in,
		// one answer out, and the stream ends when the request body does.
		Map<String, Object> result = Json
			.object(call("/v3/lease/keepalive", Json.write().integer("ID", lease).toString()).get("result"));
		return result != null && Json.integer(result.get("TTL"), 0L) > 0;
	}

	/**
	 * Revokes a lease, so that a key which no longer needs it does not leave one behind.
	 * A lease this backend grants is attached to exactly one key, and it is only revoked
	 * once that key has been moved off it or removed, so revoking never takes a key with
	 * it.
	 *
	 * <p>
	 * Failure is logged and swallowed: an unrevoked lease expires on its own, and nothing
	 * a caller does about it would be better than that.
	 * @param lease the lease id, or {@link #NO_LEASE} to do nothing
	 */
	void revokeLeaseQuietly(long lease) {
		if (lease == NO_LEASE) {
			return;
		}
		try {
			call("/v3/lease/revoke", Json.write().integer("ID", lease).toString());
		}
		catch (RuntimeException e) {
			logger.debug("Could not revoke lease {}; it will expire on its own", lease, e);
		}
	}

	// --- watch -------------------------------------------------------------------------

	/**
	 * Returns the revision the cluster is at.
	 *
	 * <p>
	 * A watch opened with no revision streams what happens from the moment etcd receives
	 * the request, which leaves everything between a store being built and that request
	 * arriving unheard. Reading the revision first and resuming from it closes that gap.
	 * @param key any key, read only for the revision its answer carries
	 * @return the current revision
	 */
	long revision(byte[] key) {
		Map<String, Object> response = call("/v3/kv/range",
				Json.write().bytes("key", key).flag("count_only", true).toString());
		return Json.integer(header(response).get("revision"), 0L);
	}

	/**
	 * Opens a watch over a range of keys.
	 *
	 * <p>
	 * The stream is the gateway's rendering of etcd's watch: one JSON object per line,
	 * for as long as the caller keeps reading. The caller closes it to cancel the watch.
	 * @param key the first key of the range
	 * @param rangeEnd the key just past the range
	 * @param startRevision the revision to start from, or {@code 0} to start from now
	 * @return the stream of watch responses, one JSON object per line
	 * @throws EtcdException if no endpoint could be reached
	 */
	InputStream watch(byte[] key, byte[] rangeEnd, long startRevision) {
		Json.Writer create = Json.write()
			.bytes("key", key)
			.bytes("range_end", rangeEnd)
			// What was there before a key was removed is the only thing that says why it
			// was removed, so every watcher asks for it.
			.flag("prev_kv", true);
		if (startRevision > 0) {
			create.integer("start_revision", startRevision);
		}
		String body = Json.write().object("create_request", create).toString();
		// No request timeout: a watch is idle exactly when nothing is happening, which is
		// most of the time.
		HttpResponse<InputStream> response = send("/v3/watch", body, HttpResponse.BodyHandlers.ofInputStream(), null);
		if (response.statusCode() != 200) {
			String refused;
			try (InputStream refusal = response.body()) {
				refused = new String(refusal.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException e) {
				throw new EtcdException("etcd refused the watch with HTTP " + response.statusCode(), e);
			}
			forgetTokenIfRejected(refused);
			throw new EtcdException("etcd refused the watch with HTTP " + response.statusCode() + ": " + refused);
		}
		return response.body();
	}

	// --- transport ---------------------------------------------------------------------

	/**
	 * Sends one request and reads the answer.
	 * @param path the gateway path
	 * @param body the request body
	 * @return the parsed response
	 * @throws EtcdException if no endpoint could be reached, or if etcd refused the
	 * request
	 */
	private Map<String, Object> call(String path, String body) {
		HttpResponse<String> response = send(path, body, HttpResponse.BodyHandlers.ofString(), this.requestTimeout);
		Map<String, Object> parsed = Json.parseObject(response.body());
		if (response.statusCode() == 200) {
			return parsed;
		}
		String message = Objects.requireNonNullElse(Json.text(parsed.get("message")), response.body());
		if (isTokenRejected(message)) {
			// The token aged out or the cluster restarted. One retry with a fresh one is
			// the difference between a store that keeps working and one that needs the
			// adapter restarted.
			this.token = null;
			logger.debug("etcd rejected the authentication token; authenticating again");
			HttpResponse<String> retried = send(path, body, HttpResponse.BodyHandlers.ofString(), this.requestTimeout);
			Map<String, Object> reparsed = Json.parseObject(retried.body());
			if (retried.statusCode() == 200) {
				return reparsed;
			}
			message = Objects.requireNonNullElse(Json.text(reparsed.get("message")), retried.body());
		}
		throw new EtcdException("etcd refused " + path + ": " + message);
	}

	private <T> HttpResponse<T> send(String path, String body, HttpResponse.BodyHandler<T> handler,
			@Nullable Duration timeout) {
		int endpoints = this.endpoints.size();
		int first = this.endpoint.get();
		IOException failure = null;
		for (int attempt = 0; attempt < endpoints; attempt++) {
			int index = Math.floorMod(first + attempt, endpoints);
			URI endpoint = this.endpoints.get(index);
			HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
			if (timeout != null) {
				request.timeout(timeout);
			}
			String token = authenticationToken(endpoint);
			if (token != null) {
				request.header("Authorization", token);
			}
			try {
				HttpResponse<T> response = this.http.send(request.build(), handler);
				this.endpoint.set(index);
				return response;
			}
			catch (IOException e) {
				// Only a transport failure means this member is not serving; anything
				// etcd
				// answers, refusals included, comes back above.
				logger.debug("etcd endpoint {} could not be reached", endpoint, e);
				failure = e;
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new EtcdException("Interrupted while waiting for etcd at " + endpoint, e);
			}
		}
		throw new EtcdException("No etcd endpoint could be reached: " + this.endpoints,
				Objects.requireNonNull(failure));
	}

	/**
	 * Returns the token to send, authenticating first if there is none yet.
	 * @param endpoint the endpoint the request is going to
	 * @return the token, or {@code null} when no user is configured
	 */
	private @Nullable String authenticationToken(URI endpoint) {
		String username = this.username;
		if (username == null) {
			return null;
		}
		String token = this.token;
		if (token != null) {
			return token;
		}
		String body = Json.write()
			.text("name", username)
			.text("password", Objects.requireNonNullElse(this.password, ""))
			.toString();
		HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/v3/auth/authenticate"))
			.header("Content-Type", "application/json")
			.timeout(this.requestTimeout)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		try {
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			Map<String, Object> parsed = Json.parseObject(response.body());
			String granted = Json.text(parsed.get("token"));
			if (response.statusCode() != 200 || granted == null) {
				// The credentials are never in the message; the reason etcd gives is.
				throw new EtcdException("etcd refused the credentials of user " + username + ": "
						+ Objects.requireNonNullElse(Json.text(parsed.get("message")), response.body()));
			}
			this.token = granted;
			return granted;
		}
		catch (IOException e) {
			throw new EtcdException("Could not authenticate against etcd at " + endpoint, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EtcdException("Interrupted while authenticating against etcd at " + endpoint, e);
		}
	}

	/**
	 * Discards the authentication token when a refusal was about the token itself.
	 *
	 * <p>
	 * An ordinary request handles that on its own, inside {@link #call}. A watch cannot:
	 * it is opened once and then read for as long as it lasts, and it is refused
	 * <em>inside the stream</em> — etcd answers HTTP 200, creates the watch and
	 * immediately cancels it, saying the token is invalid. So the one caller that reads a
	 * stream has to say when that has happened, or the watch would be opened again and
	 * again with a token the cluster has forgotten and an application would never be told
	 * another session ended.
	 * @param refusal what etcd said, as a status message or as a cancellation reason
	 * @return {@code true} if the token was discarded, so that the next request fetches a
	 * new one
	 */
	boolean forgetTokenIfRejected(String refusal) {
		if (!isTokenRejected(refusal)) {
			return false;
		}
		this.token = null;
		return true;
	}

	private static boolean isTokenRejected(String message) {
		return message.contains("invalid auth token") || message.contains("auth: token is not provided")
				|| message.contains("user name is empty");
	}

	private static @Nullable Kv firstKv(List<Object> kvs) {
		if (kvs.isEmpty()) {
			return null;
		}
		Map<String, Object> kv = Json.object(kvs.get(0));
		if (kv == null) {
			return null;
		}
		byte[] key = Json.bytes(kv.get("key"));
		if (key == null) {
			throw new EtcdException("etcd returned a key-value pair without a key: " + kv);
		}
		byte[] value = Json.bytes(kv.get("value"));
		return new Kv(key, (value != null) ? value : new byte[0], Json.integer(kv.get("mod_revision"), 0L),
				Json.integer(kv.get("lease"), NO_LEASE));
	}

	private static Map<String, Object> header(Map<String, Object> response) {
		return Objects.requireNonNullElse(Json.object(response.get("header")), Map.of());
	}

	@Override
	public void close() {
		// A watch is a request that never ends on its own, so the client is stopped
		// rather
		// than drained: whoever is reading a stream sees it close.
		this.http.shutdownNow();
	}

	/**
	 * One key-value pair as etcd returns it.
	 *
	 * @param key the etcd key, prefix and all
	 * @param value the stored bytes, which for this backend are an {@link Envelope}
	 * @param modRevision the revision the key was last modified at, which is what every
	 * read-then-write compares against
	 * @param lease the lease the key is attached to, or {@link #NO_LEASE}
	 */
	record Kv(byte[] key, byte[] value, long modRevision, long lease) {
	}

	/**
	 * Builder for {@link EtcdClient}.
	 */
	static final class Builder {

		private final List<String> endpoints = new ArrayList<>();

		private Duration connectTimeout = Duration.ofSeconds(5);

		private Duration requestTimeout = Duration.ofSeconds(5);

		private @Nullable String username;

		private @Nullable String password;

		private @Nullable SSLContext sslContext;

		private Builder() {
		}

		Builder endpoints(List<String> endpoints) {
			this.endpoints.clear();
			this.endpoints.addAll(endpoints);
			return this;
		}

		Builder connectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
			return this;
		}

		Builder requestTimeout(Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		Builder credentials(@Nullable String username, @Nullable String password) {
			this.username = username;
			this.password = password;
			return this;
		}

		Builder sslContext(@Nullable SSLContext sslContext) {
			this.sslContext = sslContext;
			return this;
		}

		EtcdClient build() {
			return new EtcdClient(this);
		}

	}

}
