package com.example.session;

import java.time.Duration;
import java.util.function.Consumer;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.ReadmeSnippets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the indexed-mode examples the README shows: the one property that asks Spring Boot
 * for an indexed repository, the {@link SessionEventListener} that reports what happens
 * to a session, and the {@link ActiveUserSessions} lookup by user.
 *
 * <p>
 * The property is taken from the README's own example rather than written out here, so a
 * reader who copies it gets what this test ran. It is registered before the context
 * refreshes because it decides which session repository Spring Boot builds.
 *
 * <p>
 * The events are asserted on the listener's own log output, because that is what the
 * README's example does with them, and they are waited for rather than read straight
 * away: every one of them is published from the client's subscription thread, well after
 * the command that caused it was answered.
 */
@SpringBootTest(classes = ReadmeIndexedModeExampleTests.Application.class)
@ExtendWith(OutputCaptureExtension.class)
class ReadmeIndexedModeExampleTests {

	/** How long an event is given to travel back through pub/sub. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Asks Spring Boot for the indexed repository, with the README's own setting.
	 * @param registry where the settings are added
	 */
	@DynamicPropertySource
	static void indexedMode(DynamicPropertyRegistry registry) {
		ReadmeSnippets.settings("readme/application.properties", "app-indexed")
			.forEach((key, value) -> registry.add(key, () -> value));
	}

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ActiveUserSessions activeSessions;

	@Test
	void theListenerExampleReportsASessionBeingCreated(CapturedOutput output) {
		Session saved = save(this.sessions, session -> session.setAttribute("user", "alice"));

		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(output).contains("session %s created".formatted(saved.getId())));
	}

	@Test
	void theListenerExampleReportsASessionBeingDeleted(CapturedOutput output) {
		Session saved = save(this.sessions, session -> session.setAttribute("user", "bob"));

		this.sessions.deleteById(saved.getId());

		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(output).contains("session %s deleted".formatted(saved.getId())));
	}

	/**
	 * The event nobody triggers: a session left alone until its time is up is reported
	 * because the backend says it dropped the key, which is the part of the story the
	 * README promises works.
	 * @param output where the listener reports
	 */
	@Test
	void theListenerExampleReportsASessionExpiringOnItsOwn(CapturedOutput output) {
		Session saved = save(this.sessions, session -> {
			session.setMaxInactiveInterval(Duration.ofSeconds(1));
			session.setAttribute("user", "carol");
		});

		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(output).contains("session %s expired".formatted(saved.getId())));
	}

	/**
	 * The principal is never shared between tests: the index has no expiry and the tests
	 * share one application, so two of them under one name would see each other's
	 * sessions.
	 */
	@Test
	void theLookupExampleFindsTheSessionsOfAUser() {
		Session saved = save(this.sessions, session -> session
			.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "readme-dave"));

		assertThat(this.activeSessions.sessionIdsOf("readme-dave")).containsExactly(saved.getId());
	}

	/**
	 * Creates, customizes and saves a session. The repository's session type is not
	 * visible from here, so it is captured as a type variable rather than named.
	 * @param repository the session repository
	 * @param customizer what to set on the new session
	 * @return the saved session
	 */
	private static <S extends Session> Session save(SessionRepository<S> repository, Consumer<Session> customizer) {
		S session = repository.createSession();
		customizer.accept(session);
		repository.save(session);
		return session;
	}

	/**
	 * The application of the README, with the adapter it talks to started beside it.
	 */
	@EnableAutoConfiguration
	@Import({ SessionEventListener.class, ActiveUserSessions.class, AdapterServerTestConfiguration.class })
	static class Application {

	}

}
