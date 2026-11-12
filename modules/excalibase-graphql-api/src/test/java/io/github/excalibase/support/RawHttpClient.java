package io.github.excalibase.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends a request target byte for byte, as curl or a browser may, where {@link java.net.http.HttpClient} would encode or refuse it. */
final class RawHttpClient {

    private final URI base;

    RawHttpClient(URI base) {
        this.base = base;
    }

    HttpAnswer get(String target) {
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: " + base.getHost()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return parse(socket.getInputStream().readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("GET " + target + " failed", failure);
        }
    }

    /** Any method, target and headers ({@code headers} alternate name, value), written exactly as given. */
    HttpAnswer send(String method, String target, String... headers) {
        StringBuilder request = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: " + base.getHost()
                + "\r\nConnection: close\r\n");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
        }
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return parse(socket.getInputStream().readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException(method + " " + target + " failed", failure);
        }
    }

    private static HttpAnswer parse(byte[] response) throws IOException {
        String text = new String(response, StandardCharsets.ISO_8859_1);
        int split = text.indexOf("\r\n\r\n");
        String[] lines = text.substring(0, split).split("\r\n");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.computeIfAbsent(lines[i].substring(0, colon).trim(), name -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).trim());
        }
        HttpHeaders parsed = HttpHeaders.of(headers, (name, value) -> true);
        byte[] payload = Arrays.copyOfRange(response, split + 4, response.length);
        boolean chunked = parsed.firstValue("transfer-encoding").map("chunked"::equalsIgnoreCase).orElse(false);
        String body = new String(chunked ? dechunk(payload) : payload, StandardCharsets.UTF_8);
        return new HttpAnswer(Integer.parseInt(lines[0].split(" ")[1]), body, parsed);
    }

    private static byte[] dechunk(byte[] payload) throws IOException {
        InputStream in = new ByteArrayInputStream(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int size = chunkSize(in); size > 0; size = chunkSize(in)) {
            out.write(in.readNBytes(size));
            in.readNBytes(2);
        }
        return out.toByteArray();
    }

    private static int chunkSize(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int next = in.read(); next != '\n' && next != -1; next = in.read()) {
            line.append((char) next);
        }
        return line.isEmpty() ? 0 : Integer.parseInt(line.toString().trim().split(";")[0], 16);
    }
}
