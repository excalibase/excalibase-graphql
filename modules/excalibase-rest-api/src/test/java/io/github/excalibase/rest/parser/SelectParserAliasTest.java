package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectParserAliasTest {

    @Test
    void anEmbedAlias_isSplitFromItsNameAndHint_atAnyDepth() {
        var embed = SelectParser.parse("id,creator:users!created_by(name,boss:users!manager(id))").embeds().getFirst();

        assertThat(embed.alias()).isEqualTo("creator");
        assertThat(embed.relationName()).isEqualTo("users");
        assertThat(embed.fkHint()).isEqualTo("created_by");
        assertThat(embed.children().getFirst().alias()).isEqualTo("boss");
        assertThat(embed.children().getFirst().fkHint()).isEqualTo("manager");
    }

    @Test
    void anEmbedWithoutAnAlias_hasNone_andAColumnAliasIsUntouched() {
        var parsed = SelectParser.parse("n:name,users!created_by(name)");

        assertThat(parsed.embeds().getFirst().alias()).isNull();
        assertThat(parsed.aliases()).containsEntry("name", "n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\", "a'b", "a\"b", "a b", "a-b", "1a", "", "__proto__", "constructor", "prototype",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void anAliasThatIsNotAPlainName_isRefused(String alias) {
        assertThatThrownBy(() -> SelectParser.parse("id," + alias + ":users!created_by(name)"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alias");
    }

    @Test
    void anAliasMayBeAnyPlainName() {
        assertThat(SelectParser.parse("id,_x9:users(name),Boss_2:users!b(id)").embeds())
                .extracting(SelectParser.EmbedSpec::alias).containsExactly("_x9", "Boss_2");
    }
}
