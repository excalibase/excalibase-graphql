package io.github.excalibase.admin;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/**
 * The console's page and assets, mounted on the admin port only, like the admin API. They carry no secret and no
 * data: the secret is typed into the page and sent to {@code /_admin/v1} by the page's own script. The policy lets
 * the page load nothing but its own files and talk to nothing but this origin, and keeps it out of every frame.
 */
@Controller(value = "/console", port = "${excalibase.admin.port:10001}")
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
public class ConsoleController {

    static final String POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final ConsoleAssets assets = new ConsoleAssets();

    /** The page at {@code /console} (a trailing slash is a 404 on every route of this engine), and any one of the listed files by name. */
    @Get("{/file}")
    public HttpResponse<byte[]> file(@Nullable String file) {
        return serve(file == null || file.isEmpty() ? "index.html" : file);
    }

    private HttpResponse<byte[]> serve(String name) {
        return assets.find(name)
                .map(asset -> HttpResponse.ok(asset.body())
                        .contentType(asset.contentType())
                        .header("Content-Security-Policy", POLICY)
                        .header("Referrer-Policy", "no-referrer"))
                .orElseGet(() -> HttpResponse.status(HttpStatus.NOT_FOUND));
    }
}
