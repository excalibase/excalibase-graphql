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
      var parsed = SelectParser.parse("id,name,email");
      assertEquals(3, parsed.columns().size());
      assertEquals("id", parsed.columns().get(0));
      assertEquals("email", parsed.columns().get(2));
      assertTrue(parsed.embeds().isEmpty());
    }

    @Test @DisplayName("single column: select=id")
    void singleColumn() {
      var parsed = SelectParser.parse("id");
      assertEquals(1, parsed.columns().size());
    }

    @Test @DisplayName("columns with spaces: select= id , name ")
    void columnsWithSpaces() {
      var parsed = SelectParser.parse(" id , name ");
      assertEquals(2, parsed.columns().size());
      assertEquals("id", parsed.columns().get(0));
      assertEquals("name", parsed.columns().get(1));
    }
  }

  @Nested
  class Wildcard {

    @Test @DisplayName("wildcard: select=* returns empty columns (means all)")
    void wildcard() {
      var parsed = SelectParser.parse("*");
      assertTrue(parsed.columns().isEmpty());
      assertTrue(parsed.embeds().isEmpty());
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
      var parsed = SelectParser.parse("id,orders(id,total)");
      assertEquals(1, parsed.columns().size());
      assertEquals(1, parsed.embeds().size());
      assertEquals("orders", parsed.embeds().get(0).relationName());
      assertEquals(2, parsed.embeds().get(0).columns().size());
      assertEquals("id", parsed.embeds().get(0).columns().get(0));
    }

    @Test @DisplayName("multiple embeds: select=id,orders(id),payments(amount)")
    void multipleEmbeds() {
      var parsed = SelectParser.parse("id,orders(id),payments(amount)");
      assertEquals(1, parsed.columns().size());
      assertEquals(2, parsed.embeds().size());
      assertEquals("orders", parsed.embeds().get(0).relationName());
      assertEquals("payments", parsed.embeds().get(1).relationName());
    }

    @Test @DisplayName("embed with all columns: select=id,orders(*)")
    void embedWildcard() {
      var parsed = SelectParser.parse("id,orders(*)");
      assertEquals(1, parsed.embeds().size());
      assertEquals("*", parsed.embeds().get(0).columns().get(0));
    }

    @Test @DisplayName("only embeds: select=orders(id,total),users(name)")
    void onlyEmbeds() {
      var parsed = SelectParser.parse("orders(id,total),users(name)");
      assertTrue(parsed.columns().isEmpty());
      assertEquals(2, parsed.embeds().size());
    }
  }

  @Nested
  class DeepEmbedding {

    @Test @DisplayName("two-level embed: orders(*,items(*)) produces child embed")
    void twoLevel() {
      var parsed = SelectParser.parse("id,orders(*,items(*))");
      assertEquals(1, parsed.columns().size());
      assertEquals(1, parsed.embeds().size());
      var orders = parsed.embeds().get(0);
      assertEquals("orders", orders.relationName());
      assertEquals(1, orders.columns().size());
      assertEquals("*", orders.columns().get(0));
      assertEquals(1, orders.children().size());
      var items = orders.children().get(0);
      assertEquals("items", items.relationName());
      assertEquals(1, items.columns().size());
      assertEquals("*", items.columns().get(0));
    }

    @Test @DisplayName("three-level embed: orders(*,items(*,products(*)))")
    void threeLevel() {
      var parsed = SelectParser.parse("orders(*,items(*,products(*)))");
      var orders = parsed.embeds().get(0);
      assertEquals("orders", orders.relationName());
      assertEquals(1, orders.children().size());
      var items = orders.children().get(0);
      assertEquals("items", items.relationName());
      assertEquals(1, items.children().size());
      var products = items.children().get(0);
      assertEquals("products", products.relationName());
      assertTrue(products.children().isEmpty());
    }

    @Test @DisplayName("deep embed with specific columns: orders(id,total,items(id,quantity))")
    void deepWithColumns() {
      var parsed = SelectParser.parse("orders(id,total,items(id,quantity))");
      var orders = parsed.embeds().get(0);
      assertEquals(2, orders.columns().size());
      assertEquals("id", orders.columns().get(0));
      assertEquals("total", orders.columns().get(1));
      assertEquals(1, orders.children().size());
      var items = orders.children().get(0);
      assertEquals(2, items.columns().size());
      assertEquals("id", items.columns().get(0));
      assertEquals("quantity", items.columns().get(1));
    }

    @Test @DisplayName("flat embed has empty children")
    void flatEmbedHasEmptyChildren() {
      var parsed = SelectParser.parse("id,orders(id,total)");
      var orders = parsed.embeds().get(0);
      assertTrue(orders.children().isEmpty());
    }
  }

  @Nested
  class Aliases {

    @Test @DisplayName("aliased column: select=fullName:first_name")
    void aliasedColumn() {
      var parsed = SelectParser.parse("fullName:first_name");
      assertEquals(1, parsed.columns().size());
      assertEquals("first_name", parsed.columns().get(0));
      assertEquals("fullName", parsed.aliases().get("first_name"));
    }

    @Test @DisplayName("FK disambiguation: select=addresses!billing_address_id(city)")
    void fkDisambiguation() {
      var parsed = SelectParser.parse("id,addresses!billing_address_id(city)");
      assertEquals(1, parsed.embeds().size());
      assertEquals("addresses", parsed.embeds().get(0).relationName());
      assertEquals("billing_address_id", parsed.embeds().get(0).fkHint());
      assertEquals("city", parsed.embeds().get(0).columns().get(0));
    }

    @Test @DisplayName("mixed aliases and normal: select=id,displayName:name")
    void mixed() {
      var parsed = SelectParser.parse("id,displayName:name");
      assertEquals(2, parsed.columns().size());
      assertEquals("displayName", parsed.aliases().get("name"));
      assertNull(parsed.aliases().get("id"));
    }
  }
}
