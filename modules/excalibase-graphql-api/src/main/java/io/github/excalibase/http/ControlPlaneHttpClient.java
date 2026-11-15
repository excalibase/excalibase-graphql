package io.github.excalibase.http;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client for every call to the control plane. It speaks HTTP/1.1: the JDK client's default offers an
 * h2c upgrade on plain http, and against a Jetty server that upgrade now and then leaves the request unanswered
 * until the server's 30 s idle timeout, whatever the request timeout says.
 */
public final class ControlPlaneHttpClient {

    private ControlPlaneHttpClient() {
    }

    public static HttpClient create(Duration connectTimeout) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
    }
}
