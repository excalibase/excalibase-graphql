const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");

const CONSOLE = path.join(__dirname, "..", "..", "modules", "excalibase-graphql-api", "src", "main", "resources", "console");
// The console scripts are ES modules with no package.json beside them, so Node 20 would read them as CommonJS.
// They import nothing, so each is loaded from a data URL.
const load = (name) => import("data:text/javascript;base64," + fs.readFileSync(path.join(CONSOLE, name)).toString("base64"));
const diffModule = () => load("diff.js");
const modelModule = () => load("docmodel.js");

const rule = (table, role, ...operations) => ({ table, role,
  ...Object.fromEntries(operations.map((operation) => [operation, { filter: {}, columns: "*" }])) });

test.describe("lineDiff", () => {
  test("identical texts have no change", async () => {
    const { lineDiff } = await diffModule();
    expect(lineDiff("a\nb\n", "a\nb\n").every((line) => line.type === "same")).toBe(true);
  });

  test("an added, a removed and a kept line, in order", async () => {
    const { lineDiff } = await diffModule();
    expect(lineDiff("a\nb\nc\n", "a\nc\nd\n")).toEqual([
      { type: "same", text: "a" }, { type: "del", text: "b" }, { type: "same", text: "c" }, { type: "add", text: "d" }]);
  });

  test("an empty side is all added or all removed", async () => {
    const { lineDiff } = await diffModule();
    expect(lineDiff("", "x\ny\n")).toEqual([{ type: "add", text: "x" }, { type: "add", text: "y" }]);
    expect(lineDiff("x\n", "")).toEqual([{ type: "del", text: "x" }]);
  });

  test("a trailing newline alone is not a change; CRLF is kept apart from LF", async () => {
    const { lineDiff } = await diffModule();
    expect(lineDiff("a\nb", "a\nb\n").every((line) => line.type === "same")).toBe(true);
    expect(lineDiff("a\r\n", "a\n").some((line) => line.type !== "same")).toBe(true);
  });

  test("two huge unrelated texts do not hang: the middle is one removal block and one addition block", async () => {
    const { lineDiff } = await diffModule();
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

  test("withContext keeps three lines around a change and marks what it hides", async () => {
    const { lineDiff, withContext } = await diffModule();
    const lines = Array.from({ length: 30 }, (_, i) => "line " + i);
    const changed = [...lines];
    changed[15] = "changed";
    const shown = withContext(lineDiff(lines.join("\n") + "\n", changed.join("\n") + "\n"), 3);
    expect(shown.filter((line) => line.type === "gap")).toHaveLength(2);
    expect(shown.filter((line) => line.type === "same")).toHaveLength(6);
    expect(shown.filter((line) => line.type !== "same" && line.type !== "gap")).toHaveLength(2);
  });
});

test.describe("describeChanges", () => {
  test("a gained operation is a gain; a lost one is not; both on one line", async () => {
    const { describeChanges } = await modelModule();
    const before = { tables: [rule("public.posts", "anon", "select", "update")] };
    const after = { tables: [rule("public.posts", "anon", "select", "delete")] };
    expect(describeChanges(before, after)).toEqual([
      { role: "anon", table: "public.posts", gained: ["delete"], lost: ["update"], changed: [], text: "anon · public.posts: + delete, - update" }]);
  });

  test("a new role or table is all gains, a removed rule all losses", async () => {
    const { describeChanges } = await modelModule();
    expect(describeChanges({ tables: [] }, { tables: [rule("public.a", "editor", "select", "insert")] })[0])
      .toMatchObject({ gained: ["select", "insert"], lost: [], text: "editor · public.a: + select, + insert" });
    expect(describeChanges({ tables: [rule("public.a", "editor", "select")] }, { tables: [] })[0])
      .toMatchObject({ gained: [], lost: ["select"] });
  });

  test("nothing changes, nothing is listed; the output is sorted by table then role", async () => {
    const { describeChanges } = await modelModule();
    const same = { tables: [rule("public.a", "user", "select")] };
    expect(describeChanges(same, same)).toEqual([]);
    const after = { tables: [rule("public.b", "anon", "select"), rule("public.a", "user", "select", "delete"),
      rule("public.a", "anon", "select")] };
    expect(describeChanges({ tables: [rule("public.a", "user", "select")] }, after).map((line) => line.text)).toEqual([
      "anon · public.a: + select", "user · public.a: + delete", "anon · public.b: + select"]);
  });

  test("a changed jwt section is listed as token settings, and is not a gain", async () => {
    const { describeChanges } = await modelModule();
    expect(describeChanges({ tables: [], jwt: { audience: "a" } }, { tables: [], jwt: { audience: "b" } }))
      .toEqual([{ role: null, table: null, gained: [], lost: [], changed: [], text: "token settings change" }]);
  });
});
