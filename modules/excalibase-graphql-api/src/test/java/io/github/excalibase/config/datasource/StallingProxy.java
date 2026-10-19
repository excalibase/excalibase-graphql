package io.github.excalibase.config.datasource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forwards to a database until {@link #stall()}: from then on every open connection stays up
 * but nothing crosses it, as when the pod behind a pooled connection is replaced by a restore
 * or failover. After {@link #resume()} only new connections forward.
 */
final class StallingProxy implements AutoCloseable {

  private final ServerSocket listener;
  private final String targetHost;
  private final int targetPort;
  private final List<AtomicBoolean> links = new CopyOnWriteArrayList<>();
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private volatile boolean stalled;

  StallingProxy(String targetHost, int targetPort) throws IOException {
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    Thread.ofVirtual().start(this::serve);
  }

  int port() {
    return listener.getLocalPort();
  }

  synchronized void stall() {
    stalled = true;
    links.forEach(dead -> dead.set(true));
  }

  synchronized void resume() {
    stalled = false;
  }

  private void serve() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        sockets.add(client);
        AtomicBoolean dead = register();
        if (dead.get()) {
          Thread.ofVirtual().start(() -> pump(client, null, dead));
          continue;
        }
        Socket upstream = new Socket(targetHost, targetPort);
        sockets.add(upstream);
        Thread.ofVirtual().start(() -> pump(upstream, client, dead));
        Thread.ofVirtual().start(() -> pump(client, upstream, dead));
      } catch (IOException closed) {
        return;
      }
    }
  }

  private synchronized AtomicBoolean register() {
    AtomicBoolean dead = new AtomicBoolean(stalled);
    links.add(dead);
    return dead;
  }

  /** Copies {@code from} to {@code to} while the link is alive; a dead link swallows everything. */
  private static void pump(Socket from, Socket to, AtomicBoolean dead) {
    byte[] buffer = new byte[32 * 1024];
    try (from) {
      InputStream in = from.getInputStream();
      OutputStream out = to == null ? null : to.getOutputStream();
      int read;
      while ((read = in.read(buffer)) >= 0) {
        if (out != null && !dead.get()) {
          out.write(buffer, 0, read);
          out.flush();
        }
      }
    } catch (IOException closed) {
      // either side went away; the other pump notices on its own read
    } finally {
      if (to != null) {
        try {
          to.close();
        } catch (IOException ignored) {
          // already closed
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    listener.close();
    for (Socket socket : sockets) {
      socket.close();
    }
  }
}
