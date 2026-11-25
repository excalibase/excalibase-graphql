package io.github.excalibase.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads an HTTP response body under the document size cap and a deadline. A request timeout ends at the headers,
 * so without this a server that sends them and then trickles or stalls would hold a poll open indefinitely.
 */
final class BoundedBody {

    private BoundedBody() {
    }

    /**
     * Reads at most one byte over the limit and then stops, so an endless body costs the limit and not the
     * server's whole answer. The stream is closed whatever happens.
     */
    static byte[] read(InputStream body, Duration deadline) throws IOException {
        CompletableFuture<byte[]> read = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (body) {
                read.complete(body.readNBytes(MetadataDocumentParser.MAX_BYTES + 1));
            } catch (IOException | RuntimeException e) {
                read.completeExceptionally(e);
            }
        });
        byte[] bytes = await(read, body, deadline);
        if (bytes.length > MetadataDocumentParser.MAX_BYTES) {
            throw new IOException(tooLarge());
        }
        return bytes;
    }

    static String tooLarge() {
        return "the document is larger than " + MetadataDocumentParser.MAX_BYTES + " bytes";
    }

    private static byte[] await(CompletableFuture<byte[]> read, InputStream body, Duration deadline)
            throws IOException {
        try {
            return read.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            body.close();
            throw new SocketTimeoutException("reading the document timed out");
        } catch (InterruptedException _) {
            body.close();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the document");
        } catch (ExecutionException e) {
            throw new IOException("reading the document failed: " + e.getCause().getClass().getSimpleName());
        }
    }
}
