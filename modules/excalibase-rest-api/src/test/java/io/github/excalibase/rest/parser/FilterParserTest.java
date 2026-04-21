package io.github.excalibase.rest.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterParserTest {

  // ─── Comparison Operators ──────────────────────────────────────────────────

  @Nested
  class ComparisonOperators {

    @Test @DisplayName("eq: ?name=eq.Alice")
    void eq() {
      var parsed = FilterParser.parse("name", "eq.Alice");
      assertEquals("name", parsed.column()); assertEquals("eq", parsed.operator());
      assertEquals("Alice", parsed.value()); assertFalse(parsed.negated());
    }

    @Test @DisplayName("neq: ?status=neq.closed")
    void neq() {
      var parsed = FilterParser.parse("status", "neq.closed");
      assertEquals("neq", parsed.operator()); assertEquals("closed", parsed.value());
    }

    @Test @DisplayName("gt: ?age=gt.18")
    void gt() {
      var parsed = FilterParser.parse("age", "gt.18");
      assertEquals("gt", parsed.operator()); assertEquals("18", parsed.value());
    }

    @Test @DisplayName("gte: ?age=gte.21")
    void gte() {
      var parsed = FilterParser.parse("age", "gte.21");
      assertEquals("gte", parsed.operator());
    }

    @Test @DisplayName("lt: ?price=lt.100")
    void lt() {
      var parsed = FilterParser.parse("price", "lt.100");
      assertEquals("lt", parsed.operator());
    }

    @Test @DisplayName("lte: ?price=lte.50")
    void lte() {
      var parsed = FilterParser.parse("price", "lte.50");
      assertEquals("lte", parsed.operator());
    }
  }

  // ─── String Operators ──────────────────────────────────────────────────────

  @Nested
  class StringOperators {

    @Test @DisplayName("like: ?name=like.*john*")
    void like() {
      var parsed = FilterParser.parse("name", "like.*john*");
      assertEquals("like", parsed.operator()); assertEquals("*john*", parsed.value());
    }

    @Test @DisplayName("ilike: ?name=ilike.*john*")
    void ilike() {
      var parsed = FilterParser.parse("name", "ilike.*john*");
      assertEquals("ilike", parsed.operator());
    }

    @Test @DisplayName("startswith: ?name=startswith.Jo")
    void startswith() {
      var parsed = FilterParser.parse("name", "startswith.Jo");
      assertEquals("startswith", parsed.operator()); assertEquals("Jo", parsed.value());
    }

    @Test @DisplayName("endswith: ?name=endswith.hn")
    void endswith() {
      var parsed = FilterParser.parse("name", "endswith.hn");
      assertEquals("endswith", parsed.operator()); assertEquals("hn", parsed.value());
    }

    @Test @DisplayName("match: ?name=match.^A")
    void match() {
      var parsed = FilterParser.parse("name", "match.^A");
      assertEquals("match", parsed.operator());
    }

    @Test @DisplayName("imatch: ?name=imatch.^a")
    void imatch() {
      var parsed = FilterParser.parse("name", "imatch.^a");
      assertEquals("imatch", parsed.operator());
    }
  }

  // ─── IN / NOT IN ───────────────────────────────────────────────────────────

  @Nested
  class InOperators {

    @Test @DisplayName("in: ?id=in.(1,2,3)")
    void in() {
      var parsed = FilterParser.parse("id", "in.(1,2,3)");
      assertEquals("in", parsed.operator()); assertEquals("(1,2,3)", parsed.value());
    }

    @Test @DisplayName("notin: ?status=notin.(draft,pending)")
    void notin() {
      var parsed = FilterParser.parse("status", "notin.(draft,pending)");
      assertEquals("notin", parsed.operator());
    }
  }

  // ─── IS Operator ───────────────────────────────────────────────────────────

  @Nested
  class IsOperators {

    @Test @DisplayName("is.null")
    void isNull() {
      var parsed = FilterParser.parse("deleted_at", "is.null");
      assertEquals("is", parsed.operator()); assertEquals("null", parsed.value());
    }

    @Test @DisplayName("is.true")
    void isTrue() {
      var parsed = FilterParser.parse("active", "is.true");
      assertEquals("is", parsed.operator()); assertEquals("true", parsed.value());
    }

    @Test @DisplayName("is.false")
    void isFalse() {
      var parsed = FilterParser.parse("active", "is.false");
      assertEquals("is", parsed.operator()); assertEquals("false", parsed.value());
    }

    @Test @DisplayName("isdistinct: ?parent=isdistinct.null")
    void isdistinct() {
      var parsed = FilterParser.parse("parent", "isdistinct.null");
      assertEquals("isdistinct", parsed.operator());
    }
  }

  // ─── Negation ──────────────────────────────────────────────────────────────

  @Nested
  class NegationOperators {

    @Test @DisplayName("not.eq: ?id=not.eq.5")
    void notEq() {
      var parsed = FilterParser.parse("id", "not.eq.5");
      assertEquals("eq", parsed.operator()); assertTrue(parsed.negated()); assertEquals("5", parsed.value());
    }

    @Test @DisplayName("not.in: ?status=not.in.(a,b)")
    void notIn() {
      var parsed = FilterParser.parse("status", "not.in.(a,b)");
      assertEquals("in", parsed.operator()); assertTrue(parsed.negated());
    }

    @Test @DisplayName("not.like: ?name=not.like.*test*")
    void notLike() {
      var parsed = FilterParser.parse("name", "not.like.*test*");
      assertEquals("like", parsed.operator()); assertTrue(parsed.negated());
    }

    @Test @DisplayName("not.is.null: negated IS NULL")
    void notIsNull() {
      var parsed = FilterParser.parse("col", "not.is.null");
      assertEquals("is", parsed.operator()); assertTrue(parsed.negated()); assertEquals("null", parsed.value());
    }
  }

  // ─── Full-Text Search ──────────────────────────────────────────────────────

  @Nested
  class FullTextSearch {

    @Test @DisplayName("fts: ?desc=fts.english.word")
    void fts() {
      var parsed = FilterParser.parse("desc", "fts.english.word");
      assertEquals("fts", parsed.operator()); assertEquals("english.word", parsed.value());
    }

    @Test @DisplayName("plfts: ?desc=plfts.english.the+cat")
    void plfts() {
      var parsed = FilterParser.parse("desc", "plfts.english.the+cat");
      assertEquals("plfts", parsed.operator());
    }

    @Test @DisplayName("phfts: ?desc=phfts.english.cat")
    void phfts() {
      var parsed = FilterParser.parse("desc", "phfts.english.cat");
      assertEquals("phfts", parsed.operator());
    }

    @Test @DisplayName("wfts: ?desc=wfts.english.cat or dog")
    void wfts() {
      var parsed = FilterParser.parse("desc", "wfts.english.cat or dog");
      assertEquals("wfts", parsed.operator());
    }
  }

  // ─── JSON Operators ────────────────────────────────────────────────────────

  @Nested
  class JsonOperators {

    @Test @DisplayName("haskey: ?data=haskey.name")
    void haskey() {
      var parsed = FilterParser.parse("data", "haskey.name");
      assertEquals("haskey", parsed.operator());
    }

    @Test @DisplayName("contains: ?data=contains.{\"a\":1}")
    void contains() {
      var parsed = FilterParser.parse("data", "contains.{\"a\":1}");
      assertEquals("contains", parsed.operator());
    }

    @Test @DisplayName("jsonpath: ?data=jsonpath.$.name")
    void jsonpath() {
      var parsed = FilterParser.parse("data", "jsonpath.$.name");
      assertEquals("jsonpath", parsed.operator());
    }
  }

  // ─── Array Operators ───────────────────────────────────────────────────────

  @Nested
  class ArrayOperators {

    @Test @DisplayName("arraycontains: ?tags=arraycontains.[a,b]")
    void arraycontains() {
      var parsed = FilterParser.parse("tags", "arraycontains.[a,b]");
      assertEquals("arraycontains", parsed.operator());
    }

    @Test @DisplayName("arrayhasany: ?tags=arrayhasany.[x,y]")
    void arrayhasany() {
      var parsed = FilterParser.parse("tags", "arrayhasany.[x,y]");
      assertEquals("arrayhasany", parsed.operator());
    }

    @Test @DisplayName("arraylength: ?tags=arraylength.3")
    void arraylength() {
      var parsed = FilterParser.parse("tags", "arraylength.3");
      assertEquals("arraylength", parsed.operator()); assertEquals("3", parsed.value());
    }
  }

  // ─── Range/Geometric Operators ─────────────────────────────────────────────

  @Nested
  class RangeOperators {

    @Test @DisplayName("cs: contains @>")
    void cs() { assertEquals("cs", FilterParser.parse("parsed", "cs.[1,5]").operator()); }

    @Test @DisplayName("cd: contained by <@")
    void cd() { assertEquals("cd", FilterParser.parse("parsed", "cd.[1,10]").operator()); }

    @Test @DisplayName("ov: overlaps &&")
    void ov() { assertEquals("ov", FilterParser.parse("parsed", "ov.[2,4]").operator()); }

    @Test @DisplayName("sl: strictly left <<")
    void sl() { assertEquals("sl", FilterParser.parse("parsed", "sl.[1,5]").operator()); }

    @Test @DisplayName("sr: strictly right >>")
    void sr() { assertEquals("sr", FilterParser.parse("parsed", "sr.[1,5]").operator()); }

    @Test @DisplayName("adj: adjacent -|-")
    void adj() { assertEquals("adj", FilterParser.parse("parsed", "adj.[1,5]").operator()); }

    @Test @DisplayName("nxl: no extend left")
    void nxl() { assertEquals("nxl", FilterParser.parse("parsed", "nxl.[1,5]").operator()); }

    @Test @DisplayName("nxr: no extend right")
    void nxr() { assertEquals("nxr", FilterParser.parse("parsed", "nxr.[1,5]").operator()); }
  }

  // ─── OR / AND Logic ────────────────────────────────────────────────────────

  @Nested
  class LogicalOperators {

    @Test @DisplayName("or: ?or=(age.gt.50,status.eq.vip)")
    void or() {
      var conds = FilterParser.parseOr("(age.gt.50,status.eq.vip)");
      assertEquals(2, conds.size());
      assertEquals("age", conds.get(0).column()); assertEquals("gt", conds.get(0).operator());
      assertEquals("status", conds.get(1).column()); assertEquals("eq", conds.get(1).operator());
    }

    @Test @DisplayName("or without parens still parses")
    void orNoParens() {
      var conds = FilterParser.parseOr("age.gt.50,status.eq.vip");
      assertEquals(2, conds.size());
    }

    @Test @DisplayName("or with nested in()")
    void orWithNestedIn() {
      var conds = FilterParser.parseOr("(id.in.(1,2,3),name.eq.test)");
      assertEquals(2, conds.size());
      assertEquals("in", conds.get(0).operator());
      assertEquals("(1,2,3)", conds.get(0).value());
    }

    @Test @DisplayName("empty or returns empty list")
    void orEmpty() {
      var conds = FilterParser.parseOr("()");
      assertEquals(0, conds.size());
    }
  }

  // ─── Edge Cases ────────────────────────────────────────────────────────────

  @Nested
  class EdgeCases {

    @Test @DisplayName("value with dots: ?email=eq.user@test.com")
    void valueWithDots() {
      var parsed = FilterParser.parse("email", "eq.user@test.com");
      assertEquals("eq", parsed.operator()); assertEquals("user@test.com", parsed.value());
    }

    @Test @DisplayName("empty value: ?name=eq.")
    void emptyValue() {
      var parsed = FilterParser.parse("name", "eq.");
      assertEquals("eq", parsed.operator()); assertEquals("", parsed.value());
    }

    @Test @DisplayName("numeric value preserved as string")
    void numericValue() {
      var parsed = FilterParser.parse("id", "eq.42");
      assertEquals("42", parsed.value());
    }

    @Test @DisplayName("value with special chars: ?desc=like.*hello world*")
    void valueWithSpaces() {
      var parsed = FilterParser.parse("desc", "like.*hello world*");
      assertEquals("like", parsed.operator()); assertEquals("*hello world*", parsed.value());
    }

    @Test @DisplayName("unknown operator defaults to eq")
    void unknownOperator() {
      var parsed = FilterParser.parse("col", "foobar.value");
      assertEquals("eq", parsed.operator());
    }

    @Test @DisplayName("operator with no dot: ?col=isnotnull")
    void operatorNoDot() {
      var parsed = FilterParser.parse("col", "isnotnull");
      assertEquals("isnotnull", parsed.operator()); assertEquals("", parsed.value());
    }
  }

  // ─── splitRespectingParens ─────────────────────────────────────────────────

  @Nested
  class SplitParens {

    @Test @DisplayName("splits on comma outside parens")
    void basic() {
      var parts = FilterParser.splitRespectingParens("a,b,c");
      assertEquals(List.of("a", "b", "c"), parts);
    }

    @Test @DisplayName("preserves content inside parens")
    void preserveParens() {
      var parts = FilterParser.splitRespectingParens("a,(1,2,3),b");
      assertEquals(3, parts.size());
      assertEquals("(1,2,3)", parts.get(1));
    }

    @Test @DisplayName("nested parens")
    void nestedParens() {
      var parts = FilterParser.splitRespectingParens("a,((1,2),3),b");
      assertEquals(3, parts.size());
      assertEquals("((1,2),3)", parts.get(1));
    }
  }
}
