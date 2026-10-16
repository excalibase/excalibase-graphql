package io.github.excalibase.mysql;

import io.github.excalibase.SqlDialect.FtsVariant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlFullTextTest {

    @Test
    void plainTextRequiresEveryIndexedWordAndDropsOperators() {
        assertThat(MysqlFullText.query(FtsVariant.PLAIN, "The quick-fox +ran (an) ~away*")).isEqualTo("+quick +fox +ran +away");
    }

    @Test
    void aPhraseKeepsItsWordsInOrder() {
        assertThat(MysqlFullText.query(FtsVariant.PHRASE, "brown \"fox\" jumps")).isEqualTo("\"brown fox jumps\"");
    }

    @Test
    void rawTextIsMysqlsOwnSyntax() {
        assertThat(MysqlFullText.query(FtsVariant.RAW, "brown -fox*")).isEqualTo("brown -fox*");
    }
}
