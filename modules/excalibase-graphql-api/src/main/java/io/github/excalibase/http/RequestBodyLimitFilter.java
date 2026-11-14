package io.github.excalibase.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Refuses a request body over the limit with 413 (EXC-726), whatever its type: the container capped only
 * form bodies and left JSON unbounded. A body whose length is announced is judged before it is read; a
 * chunked one is read up to the limit and refused as soon as it goes past.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyLimitFilter.class);

    private final long maxBytes;

    public RequestBodyLimitFilter(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("The request body limit must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long announced = request.getContentLengthLong();
        if (announced > maxBytes) {
            refuse(request, response);
            return;
        }
        if (announced < 0 && isChunked(request)) {
            byte[] body = request.getInputStream().readNBytes((int) maxBytes + 1);
            if (body.length > maxBytes) {
                refuse(request, response);
                return;
            }
            chain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isChunked(HttpServletRequest request) {
        String encoding = request.getHeader("Transfer-Encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("Request refused: body over {} bytes", maxBytes);
        response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    /** The body already read, handed on as the request's input. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
