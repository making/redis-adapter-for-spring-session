package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what a performance run measured and writes it out as markdown.
 *
 * <p>
 * Two destinations, and both are wanted: the console, so that a developer watching the
 * run sees the numbers, and a file under {@code target/}, so that what goes into
 * {@code .docs/design/etcd-performance.md} is copied rather than transcribed. Numbers
 * retyped by hand are numbers nobody can check.
 *
 * <p>
 * Sections keep the tables apart — one per backend, or one per concern — in the order
 * they were measured.
 */
final class PerformanceReport {

	private final String title;

	private final Map<String, Section> sections = new LinkedHashMap<>();

	private final List<String> notes = new ArrayList<>();

	PerformanceReport(String title) {
		this.title = title;
	}

	/**
	 * Adds a measurement to a section, creating the section on first use.
	 * @param section the heading it belongs under
	 * @param measurement what was measured
	 */
	void add(String section, Measurement measurement) {
		section(section, Measurement.MARKDOWN_HEADER).rows().add(measurement.markdownRow());
	}

	/**
	 * Adds a row to a section that is not a table of measurements — a count of round
	 * trips, or what a size limit did.
	 * @param section the heading it belongs under
	 * @param header the markdown header the section's table uses, used on first row only
	 * @param row the row
	 */
	void row(String section, String header, String row) {
		section(section, header).rows().add(row);
	}

	/**
	 * Records something that is not a table — the machine, the etcd version, what a
	 * refusal said.
	 * @param note the line to keep
	 */
	void note(String note) {
		this.notes.add(note);
	}

	/**
	 * Returns the whole report as markdown.
	 * @return the report
	 */
	String markdown() {
		StringBuilder markdown = new StringBuilder("## ").append(this.title).append("\n");
		for (String note : this.notes) {
			markdown.append("\n").append(note).append("\n");
		}
		this.sections.forEach((name, section) -> {
			markdown.append("\n### ").append(name).append("\n\n").append(section.header).append("\n");
			for (String row : section.rows) {
				markdown.append(row).append("\n");
			}
		});
		return markdown.toString();
	}

	/**
	 * Writes the report to {@code target/performance/<file>} and prints it.
	 * @param file the file name, without a directory
	 */
	void write(String file) {
		String markdown = markdown();
		System.out.println(markdown);
		Path path = Path.of("target", "performance", file);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, markdown);
			System.out.println("written to " + path.toAbsolutePath());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not write " + path, ex);
		}
	}

	private Section section(String name, String header) {
		return this.sections.computeIfAbsent(name, key -> new Section(header, new ArrayList<>()));
	}

	private record Section(String header, List<String> rows) {
	}

}
