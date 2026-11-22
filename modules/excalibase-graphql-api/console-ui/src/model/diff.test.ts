import { describe, expect, test } from 'vitest';
import { lineDiff, withContext } from './diff';

describe("lineDiff", () => {
  test("identical texts have no change", () => {
    expect(lineDiff("a\nb\n", "a\nb\n").every((line) => line.type === "same")).toBe(true);
  });

  test("an added, a removed and a kept line, in order", () => {
    expect(lineDiff("a\nb\nc\n", "a\nc\nd\n")).toEqual([
      { type: "same", text: "a" }, { type: "del", text: "b" }, { type: "same", text: "c" }, { type: "add", text: "d" }]);
  });

  test("an empty side is all added or all removed", () => {
    expect(lineDiff("", "x\ny\n")).toEqual([{ type: "add", text: "x" }, { type: "add", text: "y" }]);
    expect(lineDiff("x\n", "")).toEqual([{ type: "del", text: "x" }]);
  });

  test("a trailing newline alone is not a change; CRLF is kept apart from LF", () => {
    expect(lineDiff("a\nb", "a\nb\n").every((line) => line.type === "same")).toBe(true);
    expect(lineDiff("a\r\n", "a\n").some((line) => line.type !== "same")).toBe(true);
  });

  test("two huge unrelated texts do not hang: the middle is one removal block and one addition block", () => {
    const left = Array.from({ length: 6000 }, (_, i) => "left " + i).join("\n");
    const right = Array.from({ length: 6000 }, (_, i) => "right " + i).join("\n");
    const started = Date.now();
    const result = lineDiff("same\n" + left + "\nend", "same\n" + right + "\nend");
    expect(Date.now() - started).toBeLessThan(2000);
    expect(result[0]).toEqual({ type: "same", text: "same" });
    expect(result.filter((line) => line.type === "del")).toHaveLength(6000);
    expect(result.filter((line) => line.type === "add")).toHaveLength(6000);
    expect(result.at(-1)).toEqual({ type: "same", text: "end" });
  });

  test("withContext keeps three lines around a change and marks what it hides", () => {
    const lines = Array.from({ length: 30 }, (_, i) => "line " + i);
    const changed = [...lines];
    changed[15] = "changed";
    const shown = withContext(lineDiff(lines.join("\n") + "\n", changed.join("\n") + "\n"), 3);
    expect(shown.filter((line) => line.type === "gap")).toHaveLength(2);
    expect(shown.filter((line) => line.type === "same")).toHaveLength(6);
    expect(shown.filter((line) => line.type !== "same" && line.type !== "gap")).toHaveLength(2);
  });
});

