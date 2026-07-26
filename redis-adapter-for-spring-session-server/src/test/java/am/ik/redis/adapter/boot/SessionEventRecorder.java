package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.ApplicationListener;
import org.springframework.session.events.AbstractSessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Collects the session events a Spring Session application publishes, so a test can wait
 * for one instead of guessing how long the round trip through pub/sub takes.
 *
 * <p>
 * Every event in indexed mode is published from the listener container's own thread, well
 * after the command that caused it has been answered, so an assertion made straight after
 * that command would race the event. Waiting is therefore the only correct way to assert
 * on one; the timeout bounds how long a test may hang when an event never arrives.
 *
 * <p>
 * Events are kept for the life of the application context and always looked up by session
 * id, so tests sharing a context never see each other's.
 */
public class SessionEventRecorder implements ApplicationListener<AbstractSessionEvent> {

	/** How long to wait for an event before deciding it is not coming. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final List<AbstractSessionEvent> received = new CopyOnWriteArrayList<>();

	@Override
	public void onApplicationEvent(AbstractSessionEvent event) {
		this.received.add(event);
	}

	/**
	 * Waits for one event of the given kind about the given session and returns it.
	 * @param type the kind of event to wait for
	 * @param sessionId the session the event must be about
	 * @return the first matching event
	 */
	public <E extends AbstractSessionEvent> E awaitEvent(Class<E> type, String sessionId) {
		await().atMost(TIMEOUT).until(() -> !eventsOf(type, sessionId).isEmpty());
		return eventsOf(type, sessionId).getFirst();
	}

	/**
	 * Asserts that no event of the given kind about the given session arrives within
	 * {@code within}. Proving the absence of an asynchronous event costs that wait, so
	 * keep it short.
	 * @param type the kind of event that must not arrive
	 * @param sessionId the session the event would be about
	 * @param within how long to watch for one
	 */
	public void assertNoEvent(Class<? extends AbstractSessionEvent> type, String sessionId, Duration within) {
		await().pollDelay(within)
			.atMost(within.plus(TIMEOUT))
			.untilAsserted(
					() -> assertThat(eventsOf(type, sessionId)).as("%s for session %s", type.getSimpleName(), sessionId)
						.isEmpty());
	}

	/**
	 * Returns the events of one kind about one session, in the order they arrived.
	 * @param type the kind of event
	 * @param sessionId the session the events must be about
	 * @return the matching events
	 */
	public <E extends AbstractSessionEvent> List<E> eventsOf(Class<E> type, String sessionId) {
		return this.received.stream()
			.filter(type::isInstance)
			.map(type::cast)
			.filter(event -> sessionId.equals(event.getSessionId()))
			.toList();
	}

}
