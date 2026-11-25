package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

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

  @Nested
  @DisplayName("Strict names: checked as the select is parsed")
  class StrictNames {

    private SelectParser.SelectResult strict(String select) {
      return SelectParser.parse(select, true);
    }

    private void refused(String select) {
      var failure = assertThrows(IllegalArgumentException.class, () -> strict(select), select);
      assertFalse(failure.getMessage().toLowerCase().contains("syntax"), failure.getMessage());
    }

    @Test @DisplayName("plain, underscore-led, schema-qualified, dollar, non-ASCII and 64-character names pass")
    void identifiersPass() {
      String longest = "a" + "b".repeat(63);
      var parsed = strict("id,_x1,a$b,na\u00efve,tenant_a.users!a_user(name,orders(" + longest + "))");

      assertEquals(List.of("id", "_x1", "a$b", "na\u00efve"), parsed.columns());
      assertEquals("tenant_a.users", parsed.embeds().get(0).relationName());
      assertEquals("a_user", parsed.embeds().get(0).fkHint());
      assertEquals(List.of(longest), parsed.embeds().get(0).children().get(0).columns());
    }

    @Test @DisplayName("a column, alias, embed name or hint with anything else is refused")
    void oddNamesRefused() {
      for (String odd : List.of("x\\", "a'b", "1abc", "$sys", "a b", "a-b", "\\'||sleep(2)||'", "b".repeat(65))) {
        refused(odd);
        refused("id," + odd + ":name");
        refused("id,name:" + odd);
        refused("id,users(" + odd + ")");
        refused("id,users(id,orders(" + odd + "))");
        refused("id," + odd + "(id)");
        refused("id,users!" + odd + "(id)");
        refused("id,users(id,orders!" + odd + "(id))");
        refused("id,users(id," + odd + "(id))");
        refused("id,creator:" + odd + "(id)");
        refused("id,creator:users!" + odd + "(id)");
      }
      refused("a.b");
      refused("a.b:name");
      refused("id,users!a.b(id)");
      refused("id,users(a.b)");
      refused("a.b.c(id)");
      refused("a.(id)");
    }

    @Test @DisplayName("an empty item is refused")
    void emptyItemRefused() {
      refused("id,,name");
      refused("id,users(id,,name)");
    }

    @Test @DisplayName("a double-quoted name carries spaces, dots, hashes and non-ASCII, and comes back unquoted")
    void quotedNamesPass() {
      var parsed = strict("\"first name\",full:\"na\u00efve col\",\"p.q\",\"c#1\",\"2fa\",\"x-y\",\"$sys\","
          + "users!\"Owner Ref\"(\"zip code\")");

      assertEquals(List.of("first name", "na\u00efve col", "p.q", "c#1", "2fa", "x-y", "$sys"), parsed.columns());
      assertEquals("full", parsed.aliases().get("na\u00efve col"));
      assertEquals("Owner Ref", parsed.embeds().get(0).fkHint());
      assertEquals(List.of("zip code"), parsed.embeds().get(0).columns());
    }

    @Test @DisplayName("a quoted name cannot carry a quote, a backslash, a delimiter or a control character")
    void quotedNamesStayPlain() {
      for (String odd : List.of("\"a\\\"b\"", "\"a\\\\b\"", "\"a'b\"", "\"a;b\"", "\"a,b\"", "\"a(b\"",
          "\"a)b\"", "\"a:b\"", "\"a!b\"", "\"\"", "\"a\tb\"")) {
        refused(odd);
        refused("id,users(" + odd + ")");
        refused("id,users!" + odd + "(id)");
      }
    }
  }

  @Nested
  @DisplayName("Lenient names (the default): odd names pass on to be filtered against the table's columns")
  class LenientNames {

    @Test @DisplayName("names outside the allowlist come through as written")
    void oddNamesPass() {
      var parsed = SelectParser.parse("id,x-y,2fa,a b,users!Owner Ref(c d,orders(e f))");

      assertEquals(List.of("id", "x-y", "2fa", "a b"), parsed.columns());
      assertEquals("Owner Ref", parsed.embeds().get(0).fkHint());
      assertEquals(List.of("c d"), parsed.embeds().get(0).columns());
      assertEquals(List.of("e f"), parsed.embeds().get(0).children().get(0).columns());
    }

    @Test @DisplayName("an empty item is ignored")
    void emptyItemsIgnored() {
      assertEquals(List.of("id", "name"), SelectParser.parse("id,,name").columns());
      assertEquals(List.of("id", "name"), SelectParser.parse("users(id,,name)").embeds().get(0).columns());
    }

    @Test @DisplayName("a valid quoted name is unquoted, an invalid one is left as written")
    void quotedNames() {
      var parsed = SelectParser.parse("\"first name\",\"a\\\"b\"");

      assertEquals(List.of("first name", "\"a\\\"b\""), parsed.columns());
    }

    @Test @DisplayName("an odd name warns once per kind and position, without naming the value")
    void warnsOncePerKind() {
      SelectParser.forgetWarnings();
      var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SelectParser.class);
      var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      appender.start();
      logger.addAppender(appender);
      try {
        SelectParser.parse("secret-one,secret-two,users(secret-three),secret-embed(id)");
        SelectParser.parse("secret-four");
      } finally {
        logger.detachAppender(appender);
      }

      var messages = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
      assertEquals(2, messages.size(), messages.toString());
      assertTrue(messages.stream().noneMatch(message -> message.contains("secret")), messages.toString());
    }

    @Test @DisplayName("an aliased embed keeps its alias, and its name and hint are still read")
    void aliasedEmbed() {
      var embed = SelectParser.parse("creator:users!created_by(id)").embeds().get(0);

      assertEquals("creator", embed.alias());
      assertEquals("users", embed.relationName());
      assertEquals("created_by", embed.fkHint());
    }

    @Test @DisplayName("the one-argument parse is the lenient one")
    void defaultIsLenient() {
      assertEquals(List.of("a b"), SelectParser.parse("a b").columns());
    }
  }
}
