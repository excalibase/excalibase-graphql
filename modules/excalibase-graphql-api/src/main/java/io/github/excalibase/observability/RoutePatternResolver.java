package io.github.excalibase.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The route pattern a request would have matched, for requests that never reached the dispatcher
 * because the security chain answered them first (an unknown project, a missing token). The
 * observation records that pattern for them, so the timer must as well. The lookup runs on a copy of
 * the request's attributes, leaving the real request untouched, and only for requests that were not matched.
 */
@FunctionalInterface
public interface RoutePatternResolver {

    /** The pattern, or {@code null} when no handler mapping routes the request. */
    String resolve(HttpServletRequest request);

    RoutePatternResolver NONE = request -> null;

    static RoutePatternResolver of(HandlerMappingIntrospector introspector) {
        return request -> lookup(introspector.getHandlerMappings(), request);
    }

    private static String lookup(List<HandlerMapping> mappings, HttpServletRequest request) {
        IsolatedAttributes isolated = new IsolatedAttributes(request);
        try {
            ServletRequestPathUtils.parseAndCache(isolated);
            for (HandlerMapping mapping : mappings) {
                if (mapping.getHandler(isolated) != null) {
                    Object pattern = isolated.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    return pattern == null ? null : pattern.toString();
                }
            }
        } catch (Exception noRoute) {
            return null;
        }
        return null;
    }

    /** Reads fall through to the real request; writes stay here. */
    final class IsolatedAttributes extends HttpServletRequestWrapper {

        private final Map<String, Object> written = new HashMap<>();

        IsolatedAttributes(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Object getAttribute(String name) {
            return written.containsKey(name) ? written.get(name) : super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            written.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            written.put(name, null);
        }
    }
}
