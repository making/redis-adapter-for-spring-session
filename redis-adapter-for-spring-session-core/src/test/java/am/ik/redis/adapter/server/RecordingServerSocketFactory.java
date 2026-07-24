package am.ik.redis.adapter.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketImpl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ServerSocketFactory;

import am.ik.redis.adapter.protocol.RespProtocolException;
import am.ik.redis.adapter.protocol.RespReader;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ServerSocketFactory} that keeps a copy of every byte an accepted connection
 * carries.
 *
 * <p>
 * It exists for two reasons. It lets a test see the conversation a real client had with
 * the server — which commands it sent, and whether the server ever answered one of them
 * with an error the client silently tolerated. And it puts the server's socket factory
 * seam under load with a factory that is neither the default one nor a plain subclass of
 * {@link ServerSocket}, which is the same seam TLS is plugged into.
 */
final class RecordingServerSocketFactory extends ServerSocketFactory {

	private final ByteArrayOutputStream requests = new ByteArrayOutputStream();

	private final ByteArrayOutputStream replies = new ByteArrayOutputStream();

	@Override
	public ServerSocket createServerSocket(int port) throws IOException {
		return createServerSocket(port, 0, null);
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog) throws IOException {
		return createServerSocket(port, backlog, null);
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog, @Nullable InetAddress bindAddress)
			throws IOException {
		ServerSocket socket = new RecordingServerSocket(this.requests, this.replies);
		socket.bind(new InetSocketAddress(bindAddress, port), backlog);
		return socket;
	}

	/**
	 * Returns the name of every command the clients sent, upper-cased and in order.
	 * @return the observed command names
	 */
	List<String> commandNames() {
		List<String> names = new ArrayList<>();
		RespReader reader = new RespReader(new ByteArrayInputStream(this.requests.toByteArray()));
		try {
			List<byte[]> argv = reader.readCommand();
			while (argv != null) {
				names.add(new String(argv.get(0), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT));
				argv = reader.readCommand();
			}
		}
		catch (IOException | RespProtocolException e) {
			// The recording can stop in the middle of a frame; what parsed is what was
			// seen.
		}
		return names;
	}

	/**
	 * Returns every error reply the server wrote. Error replies are line-framed and the
	 * conversations recorded here carry no binary payload, so scanning lines is exact.
	 * @return the observed error replies
	 */
	List<String> errorReplies() {
		List<String> errors = new ArrayList<>();
		for (String line : replies().split("\r\n")) {
			if (line.startsWith("-")) {
				errors.add(line);
			}
		}
		return errors;
	}

	/**
	 * Returns everything the server wrote, byte for byte.
	 * @return the reply bytes as Latin-1 text
	 */
	String replies() {
		return this.replies.toString(StandardCharsets.ISO_8859_1);
	}

	private static final class RecordingServerSocket extends ServerSocket {

		private final ByteArrayOutputStream requests;

		private final ByteArrayOutputStream replies;

		private RecordingServerSocket(ByteArrayOutputStream requests, ByteArrayOutputStream replies)
				throws IOException {
			this.requests = requests;
			this.replies = replies;
		}

		@Override
		public Socket accept() throws IOException {
			RecordingSocket socket = new RecordingSocket(this.requests, this.replies);
			implAccept(socket);
			return socket;
		}

	}

	private static final class RecordingSocket extends Socket {

		private final ByteArrayOutputStream requests;

		private final ByteArrayOutputStream replies;

		private RecordingSocket(ByteArrayOutputStream requests, ByteArrayOutputStream replies) throws SocketException {
			super((SocketImpl) null);
			this.requests = requests;
			this.replies = replies;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return new TeeInputStream(super.getInputStream(), this.requests);
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			return new TeeOutputStream(super.getOutputStream(), this.replies);
		}

	}

	private static final class TeeInputStream extends FilterInputStream {

		private final ByteArrayOutputStream recording;

		private TeeInputStream(InputStream in, ByteArrayOutputStream recording) {
			super(in);
			this.recording = recording;
		}

		@Override
		public int read() throws IOException {
			int read = super.read();
			if (read != -1) {
				this.recording.write(read);
			}
			return read;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = super.read(buffer, offset, length);
			if (read > 0) {
				this.recording.write(buffer, offset, read);
			}
			return read;
		}

	}

	private static final class TeeOutputStream extends FilterOutputStream {

		private final ByteArrayOutputStream recording;

		private TeeOutputStream(OutputStream out, ByteArrayOutputStream recording) {
			super(out);
			this.recording = recording;
		}

		@Override
		public void write(int b) throws IOException {
			this.out.write(b);
			this.recording.write(b);
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			this.out.write(buffer, offset, length);
			this.recording.write(buffer, offset, length);
		}

	}

}
