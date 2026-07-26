package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.core.io.ClassPathResource;

/**
 * Reads the examples out of the README and out of the files they are taken from, so that
 * a test can compare the two and run the second.
 *
 * <p>
 * An example in the README is a fenced block preceded by {@code <!-- snippet:name -->};
 * the file it comes from marks the same name with {@code tag::name[]} and
 * {@code end::name[]}, the include markers Asciidoctor uses. Nothing generates one from
 * the other — {@link ReadmeExamplesTests} fails when they differ, which is what keeps a
 * documented example the code that is actually compiled and run.
 */
public final class ReadmeSnippets {

	/**
	 * The README, from the directory a test runs in, which is the module's own.
	 */
	public static final Path README = Path.of("..", "README.md");

	private static final Pattern MARKER = Pattern.compile("<!--\\s*(\\S+)\\s*-->");

	private static final Pattern SNIPPET_MARKER = Pattern.compile("<!--\\s*snippet:(\\S+)\\s*-->");

	private static final Pattern FENCE = Pattern.compile("```(\\S*)\\s*");

	private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

	/** How wide a tab is rendered as, since the README is written with spaces. */
	private static final int TAB_WIDTH = 4;

	private ReadmeSnippets() {
	}

	/**
	 * One fenced block of the README.
	 *
	 * @param name the snippet it was marked with, or {@code null} if it was not marked
	 * @param language the language the fence opened with, empty if it opened with none
	 * @param content the lines between the fences
	 */
	public record Block(@Nullable String name, String language, String content) {
	}

	/**
	 * Returns every fenced block of the README, in the order they appear.
	 * @return the blocks
	 */
	public static List<Block> blocks() {
		List<Block> blocks = new ArrayList<>();
		List<String> lines = readme().lines().toList();
		String name = null;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			Matcher marker = SNIPPET_MARKER.matcher(line);
			if (marker.matches()) {
				name = marker.group(1);
				continue;
			}
			Matcher fence = FENCE.matcher(line);
			if (!fence.matches()) {
				// Anything but a blank line between the marker and its block would mean
				// the block is somebody else's.
				if (!line.isBlank()) {
					name = null;
				}
				continue;
			}
			List<String> content = new ArrayList<>();
			while (++i < lines.size() && !lines.get(i).startsWith("```")) {
				content.add(lines.get(i));
			}
			blocks.add(new Block(name, fence.group(1), String.join("\n", content)));
			name = null;
		}
		return blocks;
	}

	/**
	 * Returns the region a source file marks with the given name, with tabs rendered the
	 * way the README writes them.
	 * @param file the file the example lives in, relative to the module directory
	 * @param name the name of the region
	 * @return the lines between the markers
	 */
	public static String region(Path file, String name) {
		return region(read(file), name);
	}

	/**
	 * Returns the region a class path resource marks with the given name.
	 * @param resource the resource the example lives in
	 * @param name the name of the region
	 * @return the lines between the markers
	 */
	public static String regionOfResource(String resource, String name) {
		return region(readResource(resource), name);
	}

	/**
	 * Returns the region marked with the given name, with tabs rendered the way the
	 * README writes them.
	 * @param content the text holding the region
	 * @param name the name of the region
	 * @return the lines between the markers
	 * @throws IllegalArgumentException if the region is not there
	 */
	public static String region(String content, String name) {
		List<String> region = new ArrayList<>();
		boolean inside = false;
		boolean found = false;
		for (String line : content.lines().toList()) {
			if (line.contains("tag::" + name + "[]")) {
				inside = true;
				found = true;
			}
			else if (line.contains("end::" + name + "[]")) {
				inside = false;
			}
			else if (inside) {
				region.add(expandTabs(line));
			}
		}
		if (!found) {
			throw new IllegalArgumentException("no region is marked tag::" + name + "[]");
		}
		return String.join("\n", region).stripTrailing();
	}

	/**
	 * Reads a region of a properties file as the settings it holds, so that a test can
	 * feed an example to a real application rather than only look at it.
	 * @param resource the class path resource the region lives in
	 * @param name the name of the region
	 * @return the settings, in the order they are written
	 */
	public static Map<String, String> settings(String resource, String name) {
		Properties properties = new Properties();
		try {
			properties.load(new StringReader(regionOfResource(resource, name)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read the settings of " + name, ex);
		}
		Map<String, String> settings = new LinkedHashMap<>();
		properties.forEach((key, value) -> settings.put(String.valueOf(key), String.valueOf(value)));
		return settings;
	}

	/**
	 * Returns the first cell of every row of the table that follows the given marker.
	 * @param marker the HTML comment the table is introduced by, without its brackets
	 * @return the cells, header and separator rows left out
	 * @throws IllegalArgumentException if the marker or the table is not there
	 */
	public static List<String> tableRowHeadings(String marker) {
		List<String> lines = readme().lines().toList();
		int start = lines.indexOf("<!-- " + marker + " -->");
		if (start < 0) {
			throw new IllegalArgumentException("the README has no <!-- " + marker + " --> marker");
		}
		List<String> rows = new ArrayList<>();
		boolean inside = false;
		for (String line : lines.subList(start + 1, lines.size())) {
			if (!line.startsWith("|")) {
				if (inside) {
					break;
				}
				continue;
			}
			inside = true;
			rows.add(line);
		}
		if (rows.size() < 3) {
			throw new IllegalArgumentException("the table after <!-- " + marker + " --> has no rows");
		}
		// The first two rows are the heading and the separator under it.
		return rows.subList(2, rows.size()).stream().map(row -> row.split("\\|")[1].trim()).toList();
	}

	/**
	 * Returns the names of every marker the README carries that begins with the given
	 * prefix, which is how a module finds the tables it is not itself the owner of.
	 * @param prefix what the marker names start with, {@code properties:} say
	 * @return the marker names, brackets and comment syntax left out
	 */
	public static List<String> markers(String prefix) {
		return readme().lines()
			.map(MARKER::matcher)
			.filter(Matcher::matches)
			.map(marker -> marker.group(1))
			.filter(name -> name.startsWith(prefix))
			.toList();
	}

	/**
	 * Returns the property names a configuration table documents, which is the first cell
	 * of each of its rows written as code.
	 * @param marker the HTML comment the table is introduced by, without its brackets
	 * @return the properties the README says are bound
	 */
	public static List<String> documentedProperties(String marker) {
		return tableRowHeadings(marker).stream().flatMap(cell -> codeSpans(cell).stream()).toList();
	}

	/**
	 * Returns the names a properties record binds, the nested ones included, the way an
	 * operator writes them.
	 * @param prefix the prefix the record is bound under
	 * @param properties the record to walk
	 * @return the property names
	 */
	public static List<String> boundProperties(String prefix, Class<?> properties) {
		List<String> names = new ArrayList<>();
		for (RecordComponent component : properties.getRecordComponents()) {
			String name = prefix + "." + kebabCase(component.getName());
			if (component.getType().isRecord()) {
				names.addAll(boundProperties(name, component.getType()));
			}
			else {
				names.add(name);
			}
		}
		return names;
	}

	private static String kebabCase(String name) {
		return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns everything written as code in one piece of Markdown, which is how the
	 * tables name the commands and methods they document.
	 * @param markdown the text to read
	 * @return the code spans, in the order they appear
	 */
	public static List<String> codeSpans(String markdown) {
		return CODE_SPAN.matcher(markdown).results().map(result -> result.group(1)).toList();
	}

	private static String expandTabs(String line) {
		return line.replace("\t", " ".repeat(TAB_WIDTH));
	}

	private static String readme() {
		return read(README);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), ex);
		}
	}

	private static String readResource(String resource) {
		try {
			return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read " + resource, ex);
		}
	}

}
