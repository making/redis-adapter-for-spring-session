package am.ik.redis.adapter.foundationdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ValueTooLargeException;
import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.FDBException;
import org.jspecify.annotations.Nullable;

/**
 * The one place this backend touches the FoundationDB driver's global state, and the one
 * place its failures are given names.
 *
 * <p>
 * Two facts about {@code fdb-java} make a holder necessary rather than tidy.
 * {@link FDB#selectAPIVersion} may be called <strong>once per JVM</strong> and starts a
 * single network thread for the whole process, so the version is a property of the
 * process and not of a store; and the native library the driver is a shim over
 * ({@code libfdb_c}) is loaded by the dynamic loader rather than from the jar, so its
 * absence shows up here, as a link error, rather than at a call.
 *
 * <p>
 * Opening a database, on the other hand, is nearly free and opens no connection: a
 * cluster that is away is discovered by the first operation, not by
 * {@link #open(String, int)}. That is what lets a store own and close its own database
 * while the factory above it holds nothing until it is asked.
 */
final class FoundationDbClient {

	/** {@code transaction_too_large}: the whole transaction is over 10 MB. */
	static final int TRANSACTION_TOO_LARGE = 2101;

	/** {@code key_too_large}: one key is over 10,000 bytes. */
	static final int KEY_TOO_LARGE = 2102;

	/** {@code value_too_large}: one value is over 100,000 bytes. */
	static final int VALUE_TOO_LARGE = 2103;

	private FoundationDbClient() {
	}

	/**
	 * Opens the cluster named by a cluster file, selecting the API version if this is the
	 * first store in the process.
	 * @param clusterFile the path of the cluster file, or {@code null} for FoundationDB's
	 * own default location
	 * @param apiVersion the API version to speak, which the whole process shares
	 * @return the database, which the caller owns and closes
	 * @throws FoundationDbException if the API version disagrees with one already
	 * selected, if the native client is not installed, or if the cluster file cannot be
	 * read
	 */
	static Database open(@Nullable String clusterFile, int apiVersion) {
		FDB fdb = select(apiVersion);
		if (clusterFile != null && !Files.isReadable(Path.of(clusterFile))) {
			// The driver does not look at the file here - it opens a database against a
			// path it never reads until the first request, and then says only "No cluster
			// file found", without naming the one it was given. A path that is not there
			// is a deployment's mistake rather than an outage, so it is worth failing
			// loudly and by name; a cluster that is merely unreachable still opens
			// perfectly well, because opening makes no connection.
			throw new FoundationDbException("The FoundationDB cluster file " + clusterFile
					+ " does not exist or cannot be read. Set redis-adapter.foundationdb.cluster-file to a file the "
					+ "server can read, or cluster-file-contents to have one written");
		}
		try {
			return (clusterFile == null) ? fdb.open() : fdb.open(clusterFile);
		}
		catch (RuntimeException e) {
			throw new FoundationDbException("Could not open the FoundationDB cluster file "
					+ ((clusterFile == null) ? "(the client's default location)" : clusterFile)
					+ "; it has to hold a description the client understands", e);
		}
	}

	/**
	 * Selects the API version for the process.
	 * @param apiVersion the version to speak
	 * @return the driver
	 * @throws FoundationDbException if a different version was already selected, or the
	 * native client is missing
	 */
	private static FDB select(int apiVersion) {
		try {
			// fdb-java itself refuses a second, different version; what it will not do is
			// say which two disagreed, and every store in the process shares the answer.
			return FDB.selectAPIVersion(apiVersion);
		}
		catch (IllegalArgumentException e) {
			throw new FoundationDbException(
					"This process has already selected a different FoundationDB API version " + "than " + apiVersion
							+ "; it may be selected only once, so every store in one server has to " + "agree on it",
					e);
		}
		catch (UnsatisfiedLinkError e) {
			throw new FoundationDbException(
					"The FoundationDB native client (libfdb_c) could not be loaded. It is not in the fdb-java jar: "
							+ "install the FoundationDB client package matching the cluster, or put the library where "
							+ "the dynamic loader finds it",
					e);
		}
	}

	/**
	 * Returns the FoundationDB failure underneath a driver exception, however the futures
	 * it travelled through wrapped it.
	 * @param failure what was caught
	 * @return the FoundationDB failure, or {@code null} if it was something else
	 */
	static @Nullable FDBException unwrap(Throwable failure) {
		// The driver hands the same failure back wrapped in whatever future it travelled
		// through - a CompletionException from join, an ExecutionException from get - so
		// the code is only reachable through the chain.
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof FDBException fdb) {
				return fdb;
			}
		}
		return null;
	}

	/**
	 * Turns a driver failure into what the rest of the adapter understands.
	 *
	 * <p>
	 * The three size refusals become the SPI's {@link ValueTooLargeException}, which the
	 * command layer answers {@code ERR value too large for the backend}: the application
	 * has to keep less in the session, and telling it {@code internal error} would send
	 * it looking for a bug in the adapter instead. Everything else — an unreachable
	 * cluster, a transaction that ran out of time, a conflict that outlived its retries —
	 * is this backend's own failure.
	 * @param what the operation, for the message
	 * @param failure what was caught
	 * @return the exception to throw
	 */
	static RuntimeException translate(String what, RuntimeException failure) {
		// Database.run hands anything its body threw to Transaction.onError, which fails
		// the
		// future with it - so what a caller sees is the failure inside a
		// CompletionException,
		// including the two the SPI names and which have to reach the command layer
		// intact.
		Throwable root = failure;
		while ((root instanceof CompletionException || root instanceof ExecutionException) && root.getCause() != null) {
			root = root.getCause();
		}
		if (root instanceof TypeMismatchException || root instanceof ValueTooLargeException
				|| root instanceof FoundationDbException) {
			return (RuntimeException) root;
		}
		FDBException fdb = unwrap(root);
		if (fdb == null) {
			return (root instanceof RuntimeException runtime) ? runtime
					: new FoundationDbException(what + " failed", root);
		}
		return switch (fdb.getCode()) {
			case TRANSACTION_TOO_LARGE, KEY_TOO_LARGE, VALUE_TOO_LARGE ->
				new ValueTooLargeException(what + " does not fit in FoundationDB: " + fdb.getMessage(), fdb);
			default -> new FoundationDbException(what + " failed against FoundationDB: " + fdb.getMessage(), fdb);
		};
	}

}
