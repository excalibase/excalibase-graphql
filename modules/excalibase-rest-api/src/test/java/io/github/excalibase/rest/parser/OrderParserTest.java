package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderParserTest {

  @Test
  @DisplayName("simple: order=name → name ASC")
  void simpleOrder() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("name");
    assertEquals(1, specs.size());
    assertEquals("name", specs.get(0).column());
    assertEquals("ASC", specs.get(0).direction());
    assertNull(specs.get(0).nulls());
  }

  @Test
  @DisplayName("with direction: order=name.desc")
  void withDirection() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("name.desc");
    assertEquals("name", specs.get(0).column());
    assertEquals("DESC", specs.get(0).direction());
  }

  @Test
  @DisplayName("with nulls: order=age.desc.nullslast")
  void withNulls() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("age.desc.nullslast");
    assertEquals("age", specs.get(0).column());
    assertEquals("DESC", specs.get(0).direction());
    assertEquals("NULLS LAST", specs.get(0).nulls());
  }

  @Test
  @DisplayName("multiple: order=last_name.asc,first_name.desc")
  void multipleOrders() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("last_name.asc,first_name.desc");
    assertEquals(2, specs.size());
    assertEquals("last_name", specs.get(0).column());
    assertEquals("ASC", specs.get(0).direction());
    assertEquals("first_name", specs.get(1).column());
    assertEquals("DESC", specs.get(1).direction());
  }

  @Test
  @DisplayName("null/empty returns empty list")
  void nullOrder() {
    assertTrue(OrderParser.parse(null).isEmpty());
    assertTrue(OrderParser.parse("").isEmpty());
  }

  @Test
  @DisplayName("SQL injection in direction is sanitized to ASC")
  void sqlInjectionDirection() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("id.ASC;DROP TABLE users--");
    assertEquals("ASC", specs.get(0).direction());
  }

  @Test
  @DisplayName("unknown direction defaults to ASC")
  void unknownDirection() {
    List<OrderParser.OrderSpec> specs = OrderParser.parse("id.RANDOM");
    assertEquals("ASC", specs.get(0).direction());
  }

  @Test
  @DisplayName("case insensitive: order=name.DESC")
  void caseInsensitive() {
    assertEquals("DESC", OrderParser.parse("name.DESC").get(0).direction());
    assertEquals("DESC", OrderParser.parse("name.Desc").get(0).direction());
  }
}
