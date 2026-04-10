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
      var r = FilterParser.parse("name", "eq.Alice");
      assertEquals("name", r.column()); assertEquals("eq", r.operator());
      assertEquals("Alice", r.value()); assertFalse(r.negated());
    }

    @Test @DisplayName("neq: ?status=neq.closed")
    void neq() {
      var r = FilterParser.parse("status", "neq.closed");
      assertEquals("neq", r.operator()); assertEquals("closed", r.value());
    }

    @Test @DisplayName("gt: ?age=gt.18")
    void gt() {
      var r = FilterParser.parse("age", "gt.18");
      assertEquals("gt", r.operator()); assertEquals("18", r.value());
    }

    @Test @DisplayName("gte: ?age=gte.21")
    void gte() {
      var r = FilterParser.parse("age", "gte.21");
      assertEquals("gte", r.operator());
    }

    @Test @DisplayName("lt: ?price=lt.100")
    void lt() {
      var r = FilterParser.parse("price", "lt.100");
      assertEquals("lt", r.operator());
    }

    @Test @DisplayName("lte: ?price=lte.50")
    void lte() {
      var r = FilterParser.parse("price", "lte.50");
      assertEquals("lte", r.operator());
    }
  }

  // ─── String Operators ──────────────────────────────────────────────────────

  @Nested
  class StringOperators {

    @Test @DisplayName("like: ?name=like.*john*")
    void like() {
      var r = FilterParser.parse("name", "like.*john*");
      assertEquals("like", r.operator()); assertEquals("*john*", r.value());
    }

    @Test @DisplayName("ilike: ?name=ilike.*john*")
    void ilike() {
      var r = FilterParser.parse("name", "ilike.*john*");
      assertEquals("ilike", r.operator());
    }

    @Test @DisplayName("startswith: ?name=startswith.Jo")
    void startswith() {
      var r = FilterParser.parse("name", "startswith.Jo");
      assertEquals("startswith", r.operator()); assertEquals("Jo", r.value());
    }

    @Test @DisplayName("endswith: ?name=endswith.hn")
    void endswith() {
      var r = FilterParser.parse("name", "endswith.hn");
      assertEquals("endswith", r.operator()); assertEquals("hn", r.value());
    }

    @Test @DisplayName("match: ?name=match.^A")
    void match() {
      var r = FilterParser.parse("name", "match.^A");
      assertEquals("match", r.operator());
    }

    @Test @DisplayName("imatch: ?name=imatch.^a")
    void imatch() {
      var r = FilterParser.parse("name", "imatch.^a");
      assertEquals("imatch", r.operator());
    }
  }

  // ─── IN / NOT IN ───────────────────────────────────────────────────────────

  @Nested
  class InOperators {

    @Test @DisplayName("in: ?id=in.(1,2,3)")
    void in() {
      var r = FilterParser.parse("id", "in.(1,2,3)");
      assertEquals("in", r.operator()); assertEquals("(1,2,3)", r.value());
    }

    @Test @DisplayName("notin: ?status=notin.(draft,pending)")
    void notin() {
      var r = FilterParser.parse("status", "notin.(draft,pending)");
      assertEquals("notin", r.operator());
    }
  }

  // ─── IS Operator ───────────────────────────────────────────────────────────

  @Nested
  class IsOperators {

    @Test @DisplayName("is.null")
    void isNull() {
      var r = FilterParser.parse("deleted_at", "is.null");
      assertEquals("is", r.operator()); assertEquals("null", r.value());
    }

    @Test @DisplayName("is.true")
    void isTrue() {
      var r = FilterParser.parse("active", "is.true");
      assertEquals("is", r.operator()); assertEquals("true", r.value());
    }

    @Test @DisplayName("is.false")
    void isFalse() {
      var r = FilterParser.parse("active", "is.false");
      assertEquals("is", r.operator()); assertEquals("false", r.value());
    }

    @Test @DisplayName("isdistinct: ?parent=isdistinct.null")
    void isdistinct() {
      var r = FilterParser.parse("parent", "isdistinct.null");
      assertEquals("isdistinct", r.operator());
    }
  }

  // ─── Negation ──────────────────────────────────────────────────────────────

  @Nested
  class NegationOperators {

    @Test @DisplayName("not.eq: ?id=not.eq.5")
    void notEq() {
      var r = FilterParser.parse("id", "not.eq.5");
      assertEquals("eq", r.operator()); assertTrue(r.negated()); assertEquals("5", r.value());
    }

    @Test @DisplayName("not.in: ?status=not.in.(a,b)")
    void notIn() {
      var r = FilterParser.parse("status", "not.in.(a,b)");
      assertEquals("in", r.operator()); assertTrue(r.negated());
    }

    @Test @DisplayName("not.like: ?name=not.like.*test*")
    void notLike() {
      var r = FilterParser.parse("name", "not.like.*test*");
      assertEquals("like", r.operator()); assertTrue(r.negated());
    }

    @Test @DisplayName("not.is.null: negated IS NULL")
    void notIsNull() {
      var r = FilterParser.parse("col", "not.is.null");
      assertEquals("is", r.operator()); assertTrue(r.negated()); assertEquals("null", r.value());
    }
  }

  // ─── Full-Text Search ──────────────────────────────────────────────────────

  @Nested
  class FullTextSearch {

    @Test @DisplayName("fts: ?desc=fts.english.word")
    void fts() {
      var r = FilterParser.parse("desc", "fts.english.word");
      assertEquals("fts", r.operator()); assertEquals("english.word", r.value());
    }

    @Test @DisplayName("plfts: ?desc=plfts.english.the+cat")
    void plfts() {
      var r = FilterParser.parse("desc", "plfts.english.the+cat");
      assertEquals("plfts", r.operator());
    }

    @Test @DisplayName("phfts: ?desc=phfts.english.cat")
    void phfts() {
      var r = FilterParser.parse("desc", "phfts.english.cat");
      assertEquals("phfts", r.operator());
    }

    @Test @DisplayName("wfts: ?desc=wfts.english.cat or dog")
    void wfts() {
      var r = FilterParser.parse("desc", "wfts.english.cat or dog");
      assertEquals("wfts", r.operator());
    }
  }

  // ─── JSON Operators ────────────────────────────────────────────────────────

  @Nested
  class JsonOperators {

    @Test @DisplayName("haskey: ?data=haskey.name")
    void haskey() {
      var r = FilterParser.parse("data", "haskey.name");
      assertEquals("haskey", r.operator());
    }

    @Test @DisplayName("contains: ?data=contains.{\"a\":1}")
    void contains() {
      var r = FilterParser.parse("data", "contains.{\"a\":1}");
      assertEquals("contains", r.operator());
    }

    @Test @DisplayName("jsonpath: ?data=jsonpath.$.name")
    void jsonpath() {
      var r = FilterParser.parse("data", "jsonpath.$.name");
      assertEquals("jsonpath", r.operator());
    }
  }

  // ─── Array Operators ───────────────────────────────────────────────────────

  @Nested
  class ArrayOperators {

    @Test @DisplayName("arraycontains: ?tags=arraycontains.[a,b]")
    void arraycontains() {
      var r = FilterParser.parse("tags", "arraycontains.[a,b]");
      assertEquals("arraycontains", r.operator());
    }

    @Test @DisplayName("arrayhasany: ?tags=arrayhasany.[x,y]")
    void arrayhasany() {
      var r = FilterParser.parse("tags", "arrayhasany.[x,y]");
      assertEquals("arrayhasany", r.operator());
    }

    @Test @DisplayName("arraylength: ?tags=arraylength.3")
    void arraylength() {
      var r = FilterParser.parse("tags", "arraylength.3");
      assertEquals("arraylength", r.operator()); assertEquals("3", r.value());
    }
  }

  // ─── Range/Geometric Operators ─────────────────────────────────────────────

  @Nested
  class RangeOperators {

    @Test @DisplayName("cs: contains @>")
    void cs() { assertEquals("cs", FilterParser.parse("r", "cs.[1,5]").operator()); }

    @Test @DisplayName("cd: contained by <@")
    void cd() { assertEquals("cd", FilterParser.parse("r", "cd.[1,10]").operator()); }

    @Test @DisplayName("ov: overlaps &&")
    void ov() { assertEquals("ov", FilterParser.parse("r", "ov.[2,4]").operator()); }

    @Test @DisplayName("sl: strictly left <<")
    void sl() { assertEquals("sl", FilterParser.parse("r", "sl.[1,5]").operator()); }

    @Test @DisplayName("sr: strictly right >>")
    void sr() { assertEquals("sr", FilterParser.parse("r", "sr.[1,5]").operator()); }

    @Test @DisplayName("adj: adjacent -|-")
    void adj() { assertEquals("adj", FilterParser.parse("r", "adj.[1,5]").operator()); }

    @Test @DisplayName("nxl: no extend left")
    void nxl() { assertEquals("nxl", FilterParser.parse("r", "nxl.[1,5]").operator()); }

    @Test @DisplayName("nxr: no extend right")
    void nxr() { assertEquals("nxr", FilterParser.parse("r", "nxr.[1,5]").operator()); }
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
      var r = FilterParser.parse("email", "eq.user@test.com");
      assertEquals("eq", r.operator()); assertEquals("user@test.com", r.value());
    }

    @Test @DisplayName("empty value: ?name=eq.")
    void emptyValue() {
      var r = FilterParser.parse("name", "eq.");
      assertEquals("eq", r.operator()); assertEquals("", r.value());
    }

    @Test @DisplayName("numeric value preserved as string")
    void numericValue() {
      var r = FilterParser.parse("id", "eq.42");
      assertEquals("42", r.value());
    }

    @Test @DisplayName("value with special chars: ?desc=like.*hello world*")
    void valueWithSpaces() {
      var r = FilterParser.parse("desc", "like.*hello world*");
      assertEquals("like", r.operator()); assertEquals("*hello world*", r.value());
    }

    @Test @DisplayName("unknown operator defaults to eq")
    void unknownOperator() {
      var r = FilterParser.parse("col", "foobar.value");
      assertEquals("eq", r.operator());
    }

    @Test @DisplayName("operator with no dot: ?col=isnotnull")
    void operatorNoDot() {
      var r = FilterParser.parse("col", "isnotnull");
      assertEquals("isnotnull", r.operator()); assertEquals("", r.value());
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
