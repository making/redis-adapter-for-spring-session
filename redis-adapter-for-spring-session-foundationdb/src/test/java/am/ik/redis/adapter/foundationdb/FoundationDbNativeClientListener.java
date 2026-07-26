package am.ik.redis.adapter.foundationdb;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Loads the FoundationDB native client before any test in the JVM runs.
 *
 * <p>
 * This exists because leaving it to the fixtures was <strong>load-order dependent, and
 * silently so</strong>. Most suites reach FoundationDB through {@link FdbCluster}, which
 * loads the library on the way; but a test that needs no cluster — an unreachable one, a
 * cluster file that is not there — builds a store directly and touches
 * {@code com.apple.foundationdb.FDB} with nothing loaded. That fails once with an
 * {@code UnsatisfiedLinkError}, and because a class whose initializer threw stays broken
 * for the life of the JVM, <em>every later test</em> then fails with
 * {@code NoClassDefFoundError} — including the ones that would have loaded it. So the
 * whole module passes or fails on which class the runner happens to start with, which is
 * exactly the kind of green that means nothing.
 *
 * <p>
 * Registered through {@code META-INF/services}, so it is not something the next test has
 * to remember. It travels in this module's test-jar, which is what makes the server
 * module built on this backend safe too.
 */
public final class FoundationDbNativeClientListener implements LauncherSessionListener {

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		FoundationDbNativeClient.ensureLoaded();
	}

}
