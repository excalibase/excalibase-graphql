package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestTargetEncodingTest {

    @Test
    void escapesWhatAStrictUriParserRefuses() {
        assertThat(RequestTargetEncoding.encode("/a?b=cs.{x}&c=[1]^|\"q\""))
                .isEqualTo("/a?b=cs.%7Bx%7D&c=%5B1%5D%5E%7C%22q%22");
    }

    @Test
    void dropsAQueryParameterWithAMalformedEscapeAsTheServletContainerDid() {
        assertThat(RequestTargetEncoding.encode("/a?n=100%")).isEqualTo("/a");
        assertThat(RequestTargetEncoding.encode("/a?n=Br%&m=%41&k=%zz&j=1")).isEqualTo("/a?m=%41&j=1");
    }

    @Test
    void escapesALonePercentSignInThePath() {
        assertThat(RequestTargetEncoding.encode("/a%/b")).isEqualTo("/a%25/b");
    }

    @Test
    void leavesALegalTargetAsItWas() {
        String legal = "/p/api/v1/t?select=a,b&or=(x.eq.1,y.is.null)&e=a@b.com";
        assertThat(RequestTargetEncoding.encode(legal)).isEqualTo(legal);
    }

    @Test
    void encodesNonAsciiAsUtf8() {
        assertThat(RequestTargetEncoding.encode("/a?n=é")).isEqualTo("/a?n=%C3%A9");
    }
}
