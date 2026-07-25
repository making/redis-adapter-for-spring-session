package com.example.session;

import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user does with the example, in a browser, against two instances of it.
 *
 * <p>
 * The tests say nothing about where the sessions are kept. Each instance is started with
 * {@link TestcontainersConfiguration}, the same class {@code spring-boot:test-run} adds,
 * so the active profile decides that: an adapter each over one shared etcd by default,
 * and one real Redis behind both under {@code -Dspring.profiles.active=redis}. Both are
 * expected to pass identically — Redis is what Spring Session was written against, so a
 * test that passes there and fails against the adapter is the adapter's fault.
 *
 * <p>
 * The browser is what makes it end to end. The session is carried by a cookie the way a
 * user's is, and a cookie is not scoped to a port, so the same browser hands it to both
 * instances on {@code localhost} without the test moving anything by hand.
 */
class SessionEndToEndTests {

	private static final int INSTANCES = 2;

	private static final List<ConfigurableApplicationContext> applications = new ArrayList<>();

	private static Playwright playwright;

	private static Browser browser;

	@BeforeAll
	static void startTheApplications() {
		for (int instance = 1; instance <= INSTANCES; instance++) {
			applications.add(startApplication("app-" + instance));
		}
		playwright = Playwright.create();
		browser = playwright.chromium().launch();
	}

	@AfterAll
	static void stopTheApplications() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
		applications.reversed().forEach(ConfigurableApplicationContext::close);
		applications.clear();
	}

	@Test
	void remembersTheVisitorAcrossPageLoads() {
		try (BrowserContext browsing = browser.newContext()) {
			Page page = browsing.newPage();
			page.navigate(urlOf(0));
			signIn(page, "Alice");

			assertThat(page.textContent("#greeting")).isEqualToNormalizingWhitespace("Hello, Alice.");
			assertThat(page.textContent("#visits")).isEqualToNormalizingWhitespace("1");

			page.reload();

			assertThat(page.textContent("#visits")).isEqualToNormalizingWhitespace("2");
		}
	}

	@Test
	void forgetsTheVisitorOnSignOut() {
		try (BrowserContext browsing = browser.newContext()) {
			Page page = browsing.newPage();
			page.navigate(urlOf(0));
			signIn(page, "Bob");
			String sessionId = page.textContent("#session-id");

			page.click("#sign-out");

			assertThat(page.isVisible("#name")).isTrue();
			signIn(page, "Bob");
			assertThat(page.textContent("#session-id")).isNotEqualTo(sessionId);
			assertThat(page.textContent("#visits")).isEqualToNormalizingWhitespace("1");
		}
	}

	@Test
	void servesTheSameSessionFromEitherInstance() {
		try (BrowserContext browsing = browser.newContext()) {
			Page page = browsing.newPage();
			page.navigate(urlOf(0));
			signIn(page, "Carol");
			String sessionId = page.textContent("#session-id");
			assertThat(page.textContent("#instance")).isEqualToNormalizingWhitespace("app-1");

			page.navigate(urlOf(1));

			assertThat(page.textContent("#instance")).isEqualToNormalizingWhitespace("app-2");
			assertThat(page.textContent("#greeting")).isEqualToNormalizingWhitespace("Hello, Carol.");
			assertThat(page.textContent("#session-id")).isEqualToNormalizingWhitespace(sessionId);
			assertThat(page.textContent("#visits")).isEqualToNormalizingWhitespace("2");
		}
	}

	private static void signIn(Page page, String name) {
		page.fill("#name", name);
		page.click("#sign-in");
	}

	/**
	 * Starts one instance of the example, with the containers its profile asks for.
	 * @param instanceName the name the instance shows on the page
	 * @return the running application
	 */
	private static ConfigurableApplicationContext startApplication(String instanceName) {
		return new SpringApplicationBuilder(SessionExampleEtcdApplication.class)
			.sources(TestcontainersConfiguration.class)
			.run("--server.port=0", "--example.instance-name=" + instanceName);
	}

	private static String urlOf(int instance) {
		ConfigurableApplicationContext application = applications.get(instance);
		return "http://localhost:" + application.getEnvironment().getRequiredProperty("local.server.port") + "/";
	}

}
