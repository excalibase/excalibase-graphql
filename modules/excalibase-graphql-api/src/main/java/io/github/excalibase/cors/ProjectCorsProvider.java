package io.github.excalibase.cors;

import java.util.List;

/**
 * Source of a project's browser-origin allowlist. The list is canonical
 * absolute origins ({@code scheme://host[:port]}) or the single {@code "*"}
 * entry; empty means the project allows no browser origin.
 *
 * <p>Implementations must fail closed: when the list cannot be determined they
 * throw {@link CorsOriginsFetchException} rather than return an empty or
 * wildcard list, so the caller can tell "no origins" from "unknown".
 */
@FunctionalInterface
public interface ProjectCorsProvider {

    List<String> originsFor(String projectId);
}
