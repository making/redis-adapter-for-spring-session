package am.ik.redis.adapter.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

/**
 * A minimal Redis client that speaks RESP by hand, so tests can send exactly the bytes
 * they want — including malformed ones — and read replies line by line.
 */
final class RawRedisClient implements AutoCloseable {

	private final Socket socket;

	private final BufferedReader reader;

	private final OutputStream out;

	/**
	 * Connects to a server on the loopback interface.
	 * @param port the port to connect to
	 * @throws IOException if the connection cannot be established
	 */
	RawRedisClient(int port) throws IOException {
		this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
		this.socket.setSoTimeout(10_000);
		this.reader = new BufferedReader(
				new InputStreamReader(this.socket.getInputStream(), StandardCharsets.ISO_8859_1));
		this.out = this.socket.getOutputStream();
	}

	/**
	 * Sends a command as a well-formed RESP request.
	 * @param arguments the command name followed by its arguments, all ASCII
	 * @throws IOException if the request cannot be written
	 */
	void send(String... arguments) throws IOException {
		StringBuilder request = new StringBuilder("*").append(arguments.length).append("\r\n");
		for (String argument : arguments) {
			request.append('$').append(argument.length()).append("\r\n").append(argument).append("\r\n");
		}
		sendRaw(request.toString());
	}

	/**
	 * Sends bytes verbatim, whether or not they form a valid request.
	 * @param bytes the exact bytes to send, as Latin-1 text
	 * @throws IOException if the bytes cannot be written
	 */
	void sendRaw(String bytes) throws IOException {
		this.out.write(bytes.getBytes(StandardCharsets.ISO_8859_1));
		this.out.flush();
	}

	/**
	 * Reads the next reply line.
	 * @return the line without its CRLF, or {@code null} once the server closed the
	 * connection
	 * @throws IOException if the connection fails
	 */
	@Nullable String readLine() throws IOException {
		return this.reader.readLine();
	}

	@Override
	public void close() {
		try {
			this.socket.close();
		}
		catch (IOException e) {
			// Nothing left to do while tearing a test connection down.
		}
	}

}
