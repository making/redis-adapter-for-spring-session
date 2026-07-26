package com.example.session.visitor.web;

import com.example.session.ExampleProperties;
import com.example.session.visitor.Visitor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The one screen of the example: a form to sign in with, and a page that remembers.
 *
 * <p>
 * Every session call here is the servlet API's own. Spring Session replaces what is
 * behind {@link HttpSession} and nothing else, which is why an application that stores
 * its sessions in DynamoDB through the adapter looks exactly like one that keeps them in
 * the servlet container.
 */
@Controller
public class VisitorController {

	private static final String VISITOR = "visitor";

	private final ExampleProperties properties;

	public VisitorController(ExampleProperties properties) {
		this.properties = properties;
	}

	/**
	 * Shows the sign-in form, or the visitor the session already holds.
	 *
	 * <p>
	 * The session is asked for without creating one, so a visitor who has not signed in
	 * costs no session at all. Reading the page counts as a visit, and the count is put
	 * back into the session: Spring Session writes an attribute to the store when it is
	 * set, not when the object it points at changes.
	 * @param request the request whose session may hold a visitor
	 * @param model the model the page is rendered from
	 * @return the view name
	 */
	@GetMapping("/")
	public String index(HttpServletRequest request, Model model) {
		HttpSession session = request.getSession(false);
		Visitor visitor = (session != null) ? (Visitor) session.getAttribute(VISITOR) : null;
		if (session != null && visitor != null) {
			visitor = visitor.returning();
			session.setAttribute(VISITOR, visitor);
			model.addAttribute("sessionId", session.getId());
		}
		model.addAttribute("visitor", visitor);
		model.addAttribute("instanceName", this.properties.instanceName());
		return "index";
	}

	/**
	 * Puts the visitor in the session, which is what creates it.
	 * @param name what the visitor typed in
	 * @param request the request to create the session on
	 * @return a redirect back to the page
	 */
	@PostMapping("/sign-in")
	public String signIn(@RequestParam("name") String name, HttpServletRequest request) {
		String trimmed = name.trim();
		if (trimmed.isEmpty()) {
			return "redirect:/";
		}
		request.getSession().setAttribute(VISITOR, Visitor.arriving(trimmed));
		return "redirect:/";
	}

	/**
	 * Ends the session, which removes it from the store.
	 * @param request the request whose session is to be ended
	 * @return a redirect back to the page
	 */
	@PostMapping("/sign-out")
	public String signOut(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return "redirect:/";
	}

}
