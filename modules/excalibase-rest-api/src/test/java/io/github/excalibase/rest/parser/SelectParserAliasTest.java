package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
