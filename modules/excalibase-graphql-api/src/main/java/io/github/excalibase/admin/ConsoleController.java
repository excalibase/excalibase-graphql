package io.github.excalibase.admin;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * The console's page and assets, mounted on the admin port only, like the admin API. They carry no secret and no
 * data: the secret is typed into the page and sent to {@code /_admin/v1} by the page's own script. The policy lets
 * the page load nothing but its own files and talk to nothing but this origin, and keeps it out of every frame.
 * The built console's page also names one style element by a nonce made for that response alone, so the editor's
 * stylesheet can be added without allowing inline styles.
 */
@Controller(value = "/console", port = "${excalibase.admin.port:10001}")
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
public class ConsoleController {

    static final String LEGACY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; require-trusted-types-for 'script'";

    private static final String STYLE_SELF = "style-src 'self';";
    private static final int NONCE_BYTES = 16;
    private static final String HEAD_END = "</head>";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final ConsoleSite site;
    private final SecureRandom random = new SecureRandom();

    public ConsoleController(ConsoleSite site) {
        this.site = site;
    }

    /** The page at {@code /console} (a trailing slash is a 404 on every route of this engine). */
    @Get
    public HttpResponse<byte[]> page(@Header("Accept-Encoding") @Nullable String acceptEncoding) {
        return file(null, acceptEncoding);
    }

    /** Any one of the listed files by name; a name the site does not list is a 404. */
    @Get("/{+file}")
    public HttpResponse<byte[]> file(@Nullable String file, @Header("Accept-Encoding") @Nullable String acceptEncoding) {
        Optional<ConsoleAssets.Asset> found = site.find(file);
        if (found.isEmpty()) {
            return HttpResponse.status(HttpStatus.NOT_FOUND);
        }
        ConsoleAssets.Asset asset = found.get();
        if (asset.policy() == ConsoleAssets.Policy.LEGACY) {
            return respond(asset.body(), asset, LEGACY_POLICY);
        }
        if (asset.contentType().startsWith("text/html")) {
            return withNonce(asset);
        }
        return bundleFile(asset, acceptEncoding);
    }

    private HttpResponse<byte[]> withNonce(ConsoleAssets.Asset page) {
        String nonce = nonce();
        String html = new String(page.body(), StandardCharsets.UTF_8)
                .replace(HEAD_END, "<style nonce=\"" + nonce + "\"></style>" + HEAD_END);
        return respond(html.getBytes(StandardCharsets.UTF_8), page,
                LEGACY_POLICY.replace(STYLE_SELF, "style-src 'self' 'nonce-" + nonce + "';"));
    }

    private HttpResponse<byte[]> bundleFile(ConsoleAssets.Asset asset, String acceptEncoding) {
        MutableHttpResponse<byte[]> answer;
        Optional<byte[]> zipped = acceptsGzip(acceptEncoding) ? asset.gzip() : Optional.empty();
        if (zipped.isPresent()) {
            answer = respond(zipped.get(), asset, LEGACY_POLICY).header("Content-Encoding", "gzip");
        } else {
            answer = respond(asset.body(), asset, LEGACY_POLICY);
        }
        answer.header("Vary", "Accept-Encoding");
        return asset.immutable() ? answer.header("Cache-Control", IMMUTABLE) : answer;
    }

    private static MutableHttpResponse<byte[]> respond(byte[] body, ConsoleAssets.Asset asset, String policy) {
        return HttpResponse.ok(body)
                .contentType(asset.contentType())
                .header("Content-Security-Policy", policy)
                .header("Referrer-Policy", "no-referrer");
    }

    private String nonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the client lists gzip with a quality above zero ({@code gzip;q=0} refuses it). */
    static boolean acceptsGzip(String header) {
        if (header == null) {
            return false;
        }
        for (String token : header.split(",")) {
            String[] parts = token.split(";");
            if ("gzip".equalsIgnoreCase(parts[0].strip())) {
                return parts.length < 2 || quality(parts[1]) > 0;
            }
        }
        return false;
    }

    /** The value of {@code q=}; anything else counts as no quality given (accepted), a malformed value as refused. */
    private static double quality(String parameter) {
        String text = parameter.strip();
        if (!text.regionMatches(true, 0, "q=", 0, 2)) {
            return 1;
        }
        try {
            return Double.parseDouble(text.substring(2).strip());
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
