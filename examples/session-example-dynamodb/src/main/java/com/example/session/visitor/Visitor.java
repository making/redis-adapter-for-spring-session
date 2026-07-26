package com.example.session.visitor;

import java.io.Serializable;

/**
 * Who is looking at the page, and how many times they have looked at it.
 *
 * <p>
 * This is what the example keeps in the session, and it is an ordinary object of the
 * application's own rather than a string: Spring Session serialises it, the adapter
 * carries the bytes, and DynamoDB holds them. Nothing along that path knows what a
 * visitor is.
 *
 * <p>
 * It is {@link Serializable} because Spring Session Data Redis serialises session
 * attributes with the JDK's own serialization by default.
 *
 * @param name what the visitor typed in
 * @param visits how many times this session has loaded the page
 */
public record Visitor(String name, int visits) implements Serializable {

	/**
	 * Returns a visitor who has just signed in and not yet been shown the page.
	 *
	 * <p>
	 * The count starts at zero because every page load raises it, including the one the
	 * sign-in redirects to.
	 * @param name what the visitor typed in
	 * @return the visitor to put in the session
	 */
	public static Visitor arriving(String name) {
		return new Visitor(name, 0);
	}

	/**
	 * Returns the same visitor, one page load later.
	 * @return the visitor to put back in the session
	 */
	public Visitor returning() {
		return new Visitor(this.name, this.visits + 1);
	}

}
