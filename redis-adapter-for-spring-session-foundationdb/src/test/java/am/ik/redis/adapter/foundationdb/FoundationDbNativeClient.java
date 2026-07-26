package am.ik.redis.adapter.foundationdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The native FoundationDB client the tests need, found or fetched.
 *
 * <p>
 * {@code fdb-java} is a JNI shim, and the library it is a shim over — {@code libfdb_c} —
 * is <strong>not in the jar</strong>. A deployment installs the FoundationDB client
 * package the ordinary way; a checkout cannot assume anything is installed, and
 * {@code ./mvnw test} has to work on a machine where nothing is. So this fetches the
 * library matching the container the tests run, verifies the checksum published beside
 * it, caches it outside {@code target/} so a clean build does not fetch it again, and
 * loads it.
 *
 * <p>
 * Two things are worth knowing, and both cost an afternoon otherwise. The shim resolves
 * {@code libfdb_c} through an {@code @rpath} baked in at Apple's build machine, so no
 * environment variable this side can point it anywhere — but <strong>{@code System.load}
 * of an absolute path, before anything touches the {@code FDB} class</strong>, is enough:
 * the dynamic loader then satisfies the shim's reference from what is already in the
 * process. And on macOS the library is published only inside a {@code .pkg}, which
 * {@code xar} and {@code tar} — both present on macOS — unpack; on Linux the release
 * publishes the bare {@code .so}.
 *
 * <p>
 * The version is the one the surefire configuration passes, which is the version of both
 * the client jar and the container: a mixed pair is untested here. Someone who has a
 * matching client installed already can point at it with
 * {@code -Dfoundationdb.client=/path/to/libfdb_c}, which is the only way to use an
 * installed one — an unverified library of an unknown version would make a failure
 * unreproducible, which is the same reason every container in this repository is pinned.
 */
public final class FoundationDbNativeClient {

	private static final String VERSION = System.getProperty("foundationdb.version", "7.3.63");

	private static final String RELEASE = "https://github.com/apple/foundationdb/releases/download/" + VERSION + "/";

	private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(5);

	static {
		load();
	}

	private FoundationDbNativeClient() {
	}

	/**
	 * Makes sure the native client is loaded. Every fixture that is about to touch
	 * FoundationDB calls this first; touching this class is what runs the loading, and
	 * calling it explicitly is what says <em>when</em>.
	 */
	public static void ensureLoaded() {
		// The static initializer above has already done it.
	}

	/**
	 * Returns the version of FoundationDB the tests speak, which is the client jar's and
	 * the container's.
	 * @return the version, such as {@code 7.3.63}
	 */
	public static String version() {
		return VERSION;
	}

	private static void load() {
		String override = System.getProperty("foundationdb.client");
		Path library = (override != null) ? Path.of(override) : cached();
		System.load(library.toAbsolutePath().toString());
	}

	/**
	 * Returns the library, fetching it into the cache if it is not there yet.
	 * @return the cached library
	 */
	private static synchronized Path cached() {
		Path directory = Path.of(System.getProperty("user.home"), ".cache", "redis-adapter-for-spring-session",
				"foundationdb", VERSION);
		Path library = directory.resolve(isMac() ? "libfdb_c.dylib" : "libfdb_c.so");
		if (Files.isRegularFile(library)) {
			return library;
		}
		try {
			Files.createDirectories(directory);
			// Said out loud, because the first run on a clean machine pauses here for
			// tens
			// of megabytes and silence would look like a hang.
			System.out.println("[foundationdb] fetching the native client " + VERSION + " for " + platform() + " into "
					+ directory + " (once per machine; -Dfoundationdb.client points at an installed one)");
			Path fetched = isMac() ? fromPackage(directory) : fromSharedObject(directory);
			Files.move(fetched, library, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return library;
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not fetch the FoundationDB native client " + VERSION, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while fetching the FoundationDB native client", e);
		}
	}

	/**
	 * Fetches the bare shared object Linux releases publish.
	 * @param directory the cache directory
	 * @return the downloaded library, not yet under its final name
	 * @throws IOException if it could not be fetched
	 */
	private static Path fromSharedObject(Path directory) throws IOException {
		String name = "libfdb_c." + architecture() + ".so";
		Path downloaded = directory.resolve(name + ".part");
		download(RELEASE + name, downloaded);
		verify(downloaded, RELEASE + name + ".sha256");
		return downloaded;
	}

	/**
	 * Fetches the macOS installer and unpacks the one file in it that matters.
	 * {@code xar} splits the installer into its component packages and {@code tar} reads
	 * the clients payload; both ship with macOS, so nothing has to be installed to get
	 * the thing that would otherwise have to be installed.
	 * @param directory the cache directory
	 * @return the extracted library, not yet under its final name
	 * @throws IOException if it could not be fetched or unpacked
	 * @throws InterruptedException if the unpacking was interrupted
	 */
	private static Path fromPackage(Path directory) throws IOException, InterruptedException {
		String name = "FoundationDB-" + VERSION + "_" + (isArm() ? "arm64" : "x86_64") + ".pkg";
		Path installer = directory.resolve(name);
		download(RELEASE + name, installer);
		verify(installer, RELEASE + name + ".sha256");
		Path unpacked = Files.createTempDirectory(directory, "unpack-");
		run(unpacked, "xar", "-xf", installer.toAbsolutePath().toString());
		Path payload = unpacked.resolve("FoundationDB-clients.pkg").resolve("Payload");
		run(unpacked, "tar", "-xf", payload.toAbsolutePath().toString());
		Path library = unpacked.resolve("usr").resolve("local").resolve("lib").resolve("libfdb_c.dylib");
		if (!Files.isRegularFile(library)) {
			throw new IOException(name + " did not hold usr/local/lib/libfdb_c.dylib where it was expected");
		}
		Path extracted = directory.resolve("libfdb_c.dylib.part");
		Files.move(library, extracted, StandardCopyOption.REPLACE_EXISTING);
		delete(unpacked);
		Files.deleteIfExists(installer);
		return extracted;
	}

	private static void download(String url, Path into) throws IOException {
		try (HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.build()) {
			HttpResponse<InputStream> response = client.send(
					HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT).build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new IOException("GET " + url + " answered " + response.statusCode());
			}
			try (InputStream body = response.body()) {
				Files.copy(body, into, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading " + url, e);
		}
	}

	/**
	 * Checks what was downloaded against the checksum published beside it, so that a
	 * truncated or tampered download fails here rather than as an unexplained native
	 * crash.
	 * @param file what was downloaded
	 * @param checksumUrl where its checksum is published
	 * @throws IOException if it could not be read, or does not match
	 */
	private static void verify(Path file, String checksumUrl) throws IOException {
		Path checksumFile = Files.createTempFile("fdb-", ".sha256");
		try {
			download(checksumUrl, checksumFile);
			String published = Files.readString(checksumFile, StandardCharsets.UTF_8).strip().split("\\s+")[0];
			String actual = sha256(file);
			if (!published.equalsIgnoreCase(actual)) {
				Files.deleteIfExists(file);
				throw new IOException(file.getFileName() + " does not match its published checksum: " + actual
						+ " against " + published);
			}
		}
		finally {
			Files.deleteIfExists(checksumFile);
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream bytes = Files.newInputStream(file)) {
				byte[] buffer = new byte[1 << 16];
				for (int read = bytes.read(buffer); read > 0; read = bytes.read(buffer)) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every JDK has SHA-256", e);
		}
	}

	private static void run(Path directory, String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).directory(directory.toFile())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();
		if (process.waitFor() != 0) {
			throw new IOException(String.join(" ", command) + " failed with exit code " + process.exitValue());
		}
	}

	private static void delete(Path directory) throws IOException {
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static String platform() {
		return System.getProperty("os.name") + "/" + System.getProperty("os.arch");
	}

	private static String architecture() {
		return isArm() ? "aarch64" : "x86_64";
	}

	private static boolean isArm() {
		String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return architecture.contains("aarch64") || architecture.contains("arm64");
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

}
