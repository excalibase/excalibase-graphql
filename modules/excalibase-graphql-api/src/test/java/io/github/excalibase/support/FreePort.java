package io.github.excalibase.support;

import java.io.IOException;
import java.net.ServerSocket;

/** A port that was free a moment ago, for settings that cannot be left random. */
public final class FreePort {

    private FreePort() {
    }

    public static int find() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("no free port", failure);
        }
    }
}
