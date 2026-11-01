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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accepts ISO 8583 connections. Each connection has a reader thread; each request is handled on a small worker
 * pool, so a slow request never holds up the ones behind it on the same connection. Responses may go back in
 * any order: the peer matches them by their fields, as networks do.
 */
public final class IsoServer implements AutoCloseable {

    /** Answers a request, or returns {@code null} when the message needs no answer. */
    public interface Handler {
        IsoMessage handle(IsoMessage request);
    }

    private static final Logger log = LoggerFactory.getLogger(IsoServer.class);

    private final String name;
    private final Iso8583Codec codec;
    private final Handler handler;
    private final ServerSocket socket;
    private final ExecutorService workers;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public IsoServer(String name, String bind, int port, Iso8583Codec codec, Handler handler, int workerThreads) throws IOException {
        this.name = name;
        this.codec = codec;
        this.handler = handler;
        this.socket = new ServerSocket(port, 50, InetAddress.getByName(bind));
        this.workers = Executors.newFixedThreadPool(workerThreads, Thread.ofPlatform().name(name + "-worker-", 0).daemon().factory());
        Thread.ofPlatform().name(name + "-accept").daemon().start(this::accept);
        log.info("{} listening for ISO 8583 on {}:{}", name, bind, socket.getLocalPort());
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket connection = socket.accept();
                connection.setTcpNoDelay(true);
                connections.add(connection);
                Thread.ofPlatform().name(name + "-conn-" + connection.getPort()).daemon().start(() -> serve(connection));
            } catch (IOException e) {
                if (!closed) {
                    log.warn("{}: accept failed: {}", name, e.getMessage());
                }
            }
        }
    }

    private void serve(Socket connection) {
        String peer = connection.getRemoteSocketAddress().toString();
        log.info("{}: {} connected", name, peer);
        ReentrantLock writing = new ReentrantLock();
        try (connection; InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
            byte[] frame;
            while ((frame = Framing.read(in)) != null) {
                IsoMessage request;
                try {
                    request = codec.decode(frame);
                } catch (Iso8583Exception e) {
                    log.warn("{}: unreadable message from {}: {}", name, peer, e.getMessage());
                    continue;
                }
                try {
                    workers.execute(() -> answer(request, out, writing, peer));
                } catch (RejectedExecutionException e) {
                    return;
                }
            }
        } catch (SocketException e) {
            // the peer went away or we are closing
        } catch (IOException e) {
            log.warn("{}: connection from {} failed: {}", name, peer, e.getMessage());
        } finally {
            connections.remove(connection);
            log.info("{}: {} disconnected", name, peer);
        }
    }

    private void answer(IsoMessage request, OutputStream out, ReentrantLock writing, String peer) {
        IsoMessage response;
        try {
            response = handler.handle(request);
        } catch (RuntimeException e) {
            log.error("{}: handling {} failed", name, request.mti(), e);
            return;
        }
        if (response == null) {
            return;
        }
        writing.lock();
        try {
            Framing.write(out, codec.encode(response));
        } catch (IOException e) {
            log.warn("{}: couldn't answer {}: {}", name, peer, e.getMessage());
        } finally {
            writing.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (Socket connection : connections) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        workers.shutdownNow();
    }
}
