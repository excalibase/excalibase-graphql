package io.github.excalibase.observability;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Answers a request the firewall rejected exactly as Spring Security does by default (a bare 400), and
 * leaves the exception on the request. The Spring Security observation used to name it on
 * {@code http.server.requests}; with that observation off, the timer reads it from here.
 */
public class RejectedRequestRecorder implements RequestRejectedHandler {

    private final RequestRejectedHandler answer = new HttpStatusRequestRejectedHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException rejection) throws IOException, ServletException {
        request.setAttribute(HttpRequestTimerFilter.REJECTED_ATTRIBUTE, rejection);
        answer.handle(request, response, rejection);
    }
}
