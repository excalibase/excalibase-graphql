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
  class DeepEmbedding {

    @Test @DisplayName("two-level embed: orders(*,items(*)) produces child embed")
    void twoLevel() {
      var r = SelectParser.parse("id,orders(*,items(*))");
      assertEquals(1, r.columns().size());
      assertEquals(1, r.embeds().size());
      var orders = r.embeds().get(0);
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
      var r = SelectParser.parse("orders(*,items(*,products(*)))");
      var orders = r.embeds().get(0);
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
      var r = SelectParser.parse("orders(id,total,items(id,quantity))");
      var orders = r.embeds().get(0);
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
      var r = SelectParser.parse("id,orders(id,total)");
      var orders = r.embeds().get(0);
      assertTrue(orders.children().isEmpty());
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

    @Test @DisplayName("FK disambiguation: select=addresses!billing_address_id(city)")
    void fkDisambiguation() {
      var r = SelectParser.parse("id,addresses!billing_address_id(city)");
      assertEquals(1, r.embeds().size());
      assertEquals("addresses", r.embeds().get(0).relationName());
      assertEquals("billing_address_id", r.embeds().get(0).fkHint());
      assertEquals("city", r.embeds().get(0).columns().get(0));
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
