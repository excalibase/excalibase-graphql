package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code posts.order} and {@code posts.limit} belong to the embed named {@code posts}. (EXC-698) */
class EmbedParamsTest {

    private static final Map<String, List<String>> PARAMS = Map.of(
            "posts.order", List.of("id.desc"),
            "posts.limit", List.of("2"),
            "posts.comments.limit", List.of("1"),
            "status", List.of("eq.open"));

    private static Set<String> paths() {
        var comments = new SelectParser.EmbedSpec("comments", List.of("*"));
        var posts = new SelectParser.EmbedSpec("posts", List.of("*"), null, List.of(comments));
        return EmbedParams.paths(List.of(posts));
    }

    @Test
    void pathsNameEveryEmbed_byAliasOrName() {
        var aliased = new SelectParser.EmbedSpec("posts", List.of("*"), null, List.of(), "recent");

        assertThat(paths()).containsExactlyInAnyOrder("posts", "posts.comments");
        assertThat(EmbedParams.paths(List.of(aliased))).containsExactly("recent");
    }

    @Test
    void anEmbedParamIsNotAFilter() {
        assertThat(EmbedParams.isEmbedParam("posts.order", paths())).isTrue();
        assertThat(EmbedParams.isEmbedParam("posts.comments.limit", paths())).isTrue();
        assertThat(EmbedParams.isEmbedParam("status", paths())).isFalse();
        assertThat(EmbedParams.isEmbedParam("other.limit", paths())).isFalse();
    }

    @Test
    void orderAndLimitAreReadForAPath() {
        assertThat(EmbedParams.order("posts", PARAMS)).hasSize(1);
        assertThat(EmbedParams.order("posts", PARAMS).get(0).direction()).isEqualTo("DESC");
        assertThat(EmbedParams.limit("posts", PARAMS)).isEqualTo(2);
        assertThat(EmbedParams.limit("posts.comments", PARAMS)).isEqualTo(1);
        assertThat(EmbedParams.limit("users", PARAMS)).isNull();
    }

    @Test
    void aBadLimitIsRefused() {
        Map<String, List<String>> params = Map.of("posts.limit", List.of("many"));

        assertThatThrownBy(() -> EmbedParams.limit("posts", params))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }
}
