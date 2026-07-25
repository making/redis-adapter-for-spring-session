package com.example.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.stereotype.Component;

// The region between the markers below is quoted verbatim in README.md.
// tag::session-event-listener[]
@Component
public class SessionEventListener {

	private static final Logger logger = LoggerFactory.getLogger(SessionEventListener.class);

	@EventListener
	public void onSessionCreated(SessionCreatedEvent event) {
		logger.info("session {} created", event.getSessionId());
	}

	@EventListener
	public void onSessionExpired(SessionExpiredEvent event) {
		logger.info("session {} expired", event.getSessionId());
	}

	@EventListener
	public void onSessionDeleted(SessionDeletedEvent event) {
		logger.info("session {} deleted", event.getSessionId());
	}

}
// end::session-event-listener[]
