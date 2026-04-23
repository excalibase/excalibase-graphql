package io.github.excalibase.nosql.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindOptionsTest {

    @Test
    @DisplayName("3-arg constructor defaults cursor to null and mode off")
    void threeArgConstructor() {
        var opts = new FindOptions(10, 0, null);
        assertThat(opts.cursor()).isNull();
        assertThat(opts.cursorMode()).isFalse();
    }

    @Test
    @DisplayName("4-arg constructor enables cursor mode iff cursor is non-null")
    void fourArgConstructor() {
        var off = new FindOptions(10, 0, null, null);
        assertThat(off.cursorMode()).isFalse();

        var on = new FindOptions(10, 0, null, "abc123");
        assertThat(on.cursorMode()).isTrue();
        assertThat(on.cursor()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("limit clamps to [1, 1000] and defaults when non-positive")
    void limitClamp() {
        assertThat(new FindOptions(0, 0, null).limit()).isEqualTo(30);     // default
        assertThat(new FindOptions(-5, 0, null).limit()).isEqualTo(30);    // negative → default
        assertThat(new FindOptions(5000, 0, null).limit()).isEqualTo(1000);// clamp
        assertThat(new FindOptions(50, 0, null).limit()).isEqualTo(50);    // pass-through
    }

    @Test
    @DisplayName("offset clamps negatives to 0")
    void offsetClamp() {
        assertThat(new FindOptions(10, -1, null).offset()).isZero();
        assertThat(new FindOptions(10, 5, null).offset()).isEqualTo(5);
    }
}
