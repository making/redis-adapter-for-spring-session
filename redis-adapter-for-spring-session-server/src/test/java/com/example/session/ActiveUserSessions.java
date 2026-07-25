package com.example.session;

import java.util.Set;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

// The region between the markers below is quoted verbatim in README.md.
// tag::find-by-index-name[]
@Service
public class ActiveUserSessions {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	public ActiveUserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	public Set<String> sessionIdsOf(String username) {
		return this.sessions.findByPrincipalName(username).keySet();
	}

}
// end::find-by-index-name[]
