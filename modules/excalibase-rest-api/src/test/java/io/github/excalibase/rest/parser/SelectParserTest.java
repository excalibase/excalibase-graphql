package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectParserTest {

  @Nested
  class BasicColumns {

    @Test @DisplayName("simple columns: select=id,name,email")
    void simpleColumns() {
      var r = SelectParser.parse("id,name,email");
      assertEquals(3, r.columns().size());
      assertEquals("id", r.columns().get(0));
      assertEquals("email", r.columns().get(2));
      assertTrue(r.embeds().isEmpty());
    }

    @Test @DisplayName("single column: select=id")
    void singleColumn() {
      var r = SelectParser.parse("id");
      assertEquals(1, r.columns().size());
    }

    @Test @DisplayName("columns with spaces: select= id , name ")
    void columnsWithSpaces() {
      var r = SelectParser.parse(" id , name ");
      assertEquals(2, r.columns().size());
      assertEquals("id", r.columns().get(0));
      assertEquals("name", r.columns().get(1));
    }
  }

  @Nested
  class Wildcard {

    @Test @DisplayName("wildcard: select=* returns empty columns (means all)")
    void wildcard() {
      var r = SelectParser.parse("*");
      assertTrue(r.columns().isEmpty());
      assertTrue(r.embeds().isEmpty());
    }

    @Test @DisplayName("null select returns all")
    void nullSelect() {
      assertTrue(SelectParser.parse(null).columns().isEmpty());
    }

    @Test @DisplayName("empty select returns all")
    void emptySelect() {
      assertTrue(SelectParser.parse("").columns().isEmpty());
    }

    @Test @DisplayName("blank select returns all")
    void blankSelect() {
      assertTrue(SelectParser.parse("   ").columns().isEmpty());
    }
  }

  @Nested
  class Embedding {

    @Test @DisplayName("single embed: select=id,orders(id,total)")
    void singleEmbed() {
      var r = SelectParser.parse("id,orders(id,total)");
      assertEquals(1, r.columns().size());
      assertEquals(1, r.embeds().size());
      assertEquals("orders", r.embeds().get(0).relationName());
      assertEquals(2, r.embeds().get(0).columns().size());
      assertEquals("id", r.embeds().get(0).columns().get(0));
    }

    @Test @DisplayName("multiple embeds: select=id,orders(id),payments(amount)")
    void multipleEmbeds() {
      var r = SelectParser.parse("id,orders(id),payments(amount)");
      assertEquals(1, r.columns().size());
      assertEquals(2, r.embeds().size());
      assertEquals("orders", r.embeds().get(0).relationName());
      assertEquals("payments", r.embeds().get(1).relationName());
    }

    @Test @DisplayName("embed with all columns: select=id,orders(*)")
    void embedWildcard() {
      var r = SelectParser.parse("id,orders(*)");
      assertEquals(1, r.embeds().size());
      assertEquals("*", r.embeds().get(0).columns().get(0));
    }

    @Test @DisplayName("only embeds: select=orders(id,total),users(name)")
    void onlyEmbeds() {
      var r = SelectParser.parse("orders(id,total),users(name)");
      assertTrue(r.columns().isEmpty());
      assertEquals(2, r.embeds().size());
    }
  }

  @Nested
  class Aliases {

    @Test @DisplayName("aliased column: select=fullName:first_name")
    void aliasedColumn() {
      var r = SelectParser.parse("fullName:first_name");
      assertEquals(1, r.columns().size());
      assertEquals("first_name", r.columns().get(0));
      assertEquals("fullName", r.aliases().get("first_name"));
    }

    @Test @DisplayName("mixed aliases and normal: select=id,displayName:name")
    void mixed() {
      var r = SelectParser.parse("id,displayName:name");
      assertEquals(2, r.columns().size());
      assertEquals("displayName", r.aliases().get("name"));
      assertNull(r.aliases().get("id"));
    }
  }
}
