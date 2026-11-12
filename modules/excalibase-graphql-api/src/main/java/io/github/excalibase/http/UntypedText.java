package io.github.excalibase.http;

import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** A text body sent with no {@code Content-Type}, as the servlet stack sent CORS refusals. */
public record UntypedText(String text) {

    /** The media type a response names to have its body written without any. */
    public static final String MEDIA_TYPE = "text/x-untyped";

    @Singleton
    @Produces(MEDIA_TYPE)
    static final class Writer implements MessageBodyWriter<UntypedText> {

        @Override
        public void writeTo(Argument<UntypedText> type, MediaType mediaType, UntypedText body,
                            MutableHeaders outgoingHeaders, OutputStream outputStream) throws CodecException {
            outgoingHeaders.remove("Content-Type");
            try {
                outputStream.write(body.text().getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new CodecException("Cannot write the body: " + failure.getMessage(), failure);
            }
        }
    }
}
