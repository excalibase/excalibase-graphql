package io.github.excalibase.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;

/** The HTML page the servlet container answered a request it could not parse with. */
final class TomcatErrorPage {

    static final String CONTENT_TYPE = "text/html;charset=utf-8";
    static final String BAD_REQUEST = "<!doctype html><html lang=\"en\"><head><title>HTTP Status 400 – Bad Request"
            + "</title><style type=\"text/css\">body {font-family:Tahoma,Arial,sans-serif;} h1, h2, h3, b "
            + "{color:white;background-color:#525D76;} h1 {font-size:22px;} h2 {font-size:16px;} h3 {font-size:14px;} "
            + "p {font-size:12px;} a {color:black;} .line {height:1px;background-color:#525D76;border:none;}"
            + "</style></head><body><h1>HTTP Status 400 – Bad Request</h1></body></html>";

    private TomcatErrorPage() {
    }

    static MutableHttpResponse<String> badRequest() {
        return HttpResponse.<String>status(HttpStatus.BAD_REQUEST).contentType(CONTENT_TYPE).body(BAD_REQUEST);
    }
}
