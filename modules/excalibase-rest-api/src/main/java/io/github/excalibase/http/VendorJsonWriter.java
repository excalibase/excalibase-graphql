package io.github.excalibase.http;

import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.OutputStream;

/** The data API's own JSON media types ({@code application/vnd.pgrst.object+json}, {@code application/openapi+json}) are JSON. */
@Singleton
@Produces({"application/vnd.pgrst.object+json", "application/openapi+json"})
public final class VendorJsonWriter<T> implements MessageBodyWriter<T> {

    private final JsonMapper mapper;

    public VendorJsonWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void writeTo(Argument<T> type, MediaType mediaType, T object, MutableHeaders outgoingHeaders,
                        OutputStream outputStream) throws io.micronaut.http.codec.CodecException {
        try {
            mapper.writeValue(outputStream, type, object);
        } catch (IOException e) {
            throw new io.micronaut.http.codec.CodecException("Cannot write " + mediaType + ": " + e.getMessage(), e);
        }
    }
}
