package app.cardrail.common.iso;

import app.cardrail.iso8583.Framing;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.Iso8583Exception;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One long-lived ISO 8583 connection to another party, shared by every request. Requests are sent as they
 * come, and each response is matched to its request by {@link #key}: the trace number (field 11) and the
 * retrieval reference (field 37). A connection that fails is opened again by the next send.
 */
public final class IsoLink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IsoLink.class);

    private final String name;
    private final String host;
    private final int port;
    private final Iso8583Codec codec;
    private final Map<String, CompletableFuture<IsoMessage>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock connecting = new ReentrantLock();
    private final ReentrantLock writing = new ReentrantLock();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean closed;

    public IsoLink(String name, String host, int port, Iso8583Codec codec) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.codec = codec;
    }

    /** How a request and its response are matched. Responses echo both fields unchanged. */
    public static String key(IsoMessage message) {
        return message.get(11) + "/" + message.get(37);
    }

    /**
     * Sends a request and completes with its response, or exceptionally with {@link IsoTimeoutException} when none
     * comes in time, or {@link IOException} when it couldn't be sent. A timed-out request may still have arrived.
     */
    public CompletableFuture<IsoMessage> send(IsoMessage request, Duration timeout) {
        String key = key(request);
        CompletableFuture<IsoMessage> response = new CompletableFuture<>();
        if (pending.putIfAbsent(key, response) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("a request with key " + key + " is already waiting"));
        }
        response.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(key, response));
        try {
            byte[] bytes = codec.encode(request);
            OutputStream stream = connected();
            writing.lock();
            try {
                Framing.write(stream, bytes);
            } finally {
                writing.unlock();
            }
        } catch (IOException e) {
            drop(e);
            response.completeExceptionally(e);
        } catch (RuntimeException e) {
            response.completeExceptionally(e);
        }
        return response.exceptionallyCompose(e -> CompletableFuture.failedFuture(
                e instanceof java.util.concurrent.TimeoutException ? new IsoTimeoutException(name, timeout) : e));
    }

    /** Sends a message that gets no response (an advice whose answer we don't wait for). */
    public void sendOneWay(IsoMessage message) throws IOException {
        byte[] bytes = codec.encode(message);
        OutputStream stream = connected();
        writing.lock();
        try {
            Framing.write(stream, bytes);
        } catch (IOException e) {
            drop(e);
            throw e;
        } finally {
            writing.unlock();
        }
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    private OutputStream connected() throws IOException {
        OutputStream stream = out;
        if (stream != null) {
            return stream;
        }
        connecting.lock();
        try {
            if (out != null) {
                return out;
            }
            if (closed) {
                throw new IOException(name + " is closed");
            }
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            socket = s;
            out = new BufferedOutputStream(s.getOutputStream());
            Thread.ofPlatform().name(name + "-reader").daemon().start(() -> read(s, in));
            log.info("{}: connected to {}:{}", name, host, port);
            return out;
        } finally {
            connecting.unlock();
        }
    }

    private void read(Socket s, InputStream in) {
        try {
            byte[] frame;
            while ((frame = Framing.read(in)) != null) {
                IsoMessage response;
                try {
                    response = codec.decode(frame);
                } catch (Iso8583Exception e) {
                    log.warn("{}: unreadable response: {}", name, e.getMessage());
                    continue;
                }
                CompletableFuture<IsoMessage> waiting = pending.get(key(response));
                if (waiting == null) {
                    log.warn("{}: a {} arrived that nobody is waiting for (late, or a duplicate)", name, response.mti());
                } else {
                    waiting.complete(response);
                }
            }
            drop(new IOException("the other side closed the connection"));
        } catch (IOException e) {
            if (socket == s) {
                drop(e);
            }
        }
    }

    private void drop(IOException cause) {
        connecting.lock();
        try {
            Socket s = socket;
            socket = null;
            out = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // already failing
                }
                if (!closed) {
                    log.warn("{}: connection lost: {}", name, cause.getMessage());
                }
            }
        } finally {
            connecting.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        drop(new IOException("closing"));
    }
}
