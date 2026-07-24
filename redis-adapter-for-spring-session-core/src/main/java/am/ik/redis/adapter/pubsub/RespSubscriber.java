package am.ik.redis.adapter.pubsub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Lock;

import am.ik.redis.adapter.protocol.RespWriter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers messages to a connection as RESP push frames.
 *
 * <p>
 * A delivery is a three-element frame {@code [message, channel, body]}, or a four-element
 * {@code [pmessage, pattern, channel, body]} when a pattern subscription matched. In
 * RESP3 these go out as push frames and in RESP2 as plain arrays, which is the same
 * distinction Redis makes and which {@link RespWriter#writePushHeader(int)} already
 * encodes. The frame is flushed immediately: a subscribed connection is not sending
 * requests, so nothing else would flush it.
 *
 * <h2>Serializing the connection's output</h2> Messages are written by whichever thread
 * published them, while the connection's own thread is writing the replies to its
 * commands. Both must not interleave on one socket, so every write goes through the lock
 * this instance was given: the connection wraps its whole request/reply cycle in
 * {@link Lock#lock()} on the same lock, and a delivery waits for the reply in flight to
 * be finished and flushed.
 *
 * <p>
 * That leaves a lock per connection to acquire while publishing, which could deadlock if
 * two connections ever published to each other at the same time. They cannot: a
 * subscribed connection is the only kind that receives, and it is refused every command
 * that publishes (see {@code CommandAvailability.WHILE_SUBSCRIBED}), so a thread that
 * holds one connection's lock and waits for another's never has a thread waiting on it in
 * return.
 *
 * <p>
 * A delivery to a connection whose socket has already died is logged and dropped rather
 * than propagated: the publisher is not the one with the problem, and the dead connection
 * removes itself from the registry as soon as its own thread notices.
 */
public final class RespSubscriber implements Subscriber {

	private static final Logger logger = LoggerFactory.getLogger(RespSubscriber.class);

	private static final byte[] MESSAGE = "message".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] PATTERN_MESSAGE = "pmessage".getBytes(StandardCharsets.US_ASCII);

	private final RespWriter writer;

	private final Lock writeLock;

	/**
	 * Creates a subscriber over a connection's writer.
	 * @param writer the connection's reply writer
	 * @param writeLock the lock the connection holds while writing a reply, so that
	 * deliveries and replies never interleave on the stream
	 */
	public RespSubscriber(RespWriter writer, Lock writeLock) {
		this.writer = writer;
		this.writeLock = writeLock;
	}

	@Override
	public void message(byte[] channel, byte[] body) {
		push(MESSAGE, null, channel, body);
	}

	@Override
	public void patternMessage(byte[] pattern, byte[] channel, byte[] body) {
		push(PATTERN_MESSAGE, pattern, channel, body);
	}

	private void push(byte[] kind, byte @Nullable [] pattern, byte[] channel, byte[] body) {
		this.writeLock.lock();
		try {
			this.writer.writePushHeader((pattern == null) ? 3 : 4);
			this.writer.writeBulk(kind);
			if (pattern != null) {
				this.writer.writeBulk(pattern);
			}
			this.writer.writeBulk(channel);
			this.writer.writeBulk(body);
			this.writer.flush();
		}
		catch (IOException e) {
			logger.debug("Failed to deliver a message to a subscriber: {}", e.toString());
		}
		finally {
			this.writeLock.unlock();
		}
	}

}
