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
      .toEqual([{ role: null, table: null, gained: [], lost: [], changed: [], other: true, text: "token settings change" }]);
  });
});

test.describe("widens and exportName", () => {
  test("only a gain, a rewritten rule or a token change widens", async () => {
    const { describeChanges, widens } = await modelModule();
    const base = { tables: [rule("public.a", "user", "select", "delete")] };
    expect(widens(describeChanges(base, { tables: [rule("public.a", "user", "select")] }))).toBe(false);
    expect(widens(describeChanges(base, base))).toBe(false);
    expect(widens(describeChanges(base, { tables: [...base.tables, rule("public.a", "anon", "select")] }))).toBe(true);
    const loosened = { tables: [{ ...rule("public.a", "user", "select", "delete"), select: { filter: { id: 1 }, columns: "*" } }] };
    expect(widens(describeChanges(loosened, base))).toBe(true);
    expect(widens(describeChanges({ tables: [] }, { tables: [], jwt: { audience: "x" } }))).toBe(true);
  });

  test("a label tells two files apart and the kind follows the text", async () => {
    const { exportName } = await modelModule();
    expect(exportName("{}", "old")).toEqual({ filename: "permissions-old.json", type: "application/json" });
    expect(exportName("a: 1", "")).toEqual({ filename: "permissions.yaml", type: "text/yaml" });
  });
});

const fn = (name, extra = {}) => ({ function: name, exposedAs: "MUTATION", inferPermissions: false, sessionArgument: null, ...extra });

test.describe("functions and other top-level keys", () => {
  test("a new function permission is a gain, a removed one is a removal", async () => {
    const { describeChanges, widens } = await modelModule();
    const before = { tables: [], functions: [fn("public.wipe_all")], functionPermissions: [{ function: "public.wipe_all", role: "user" }] };
    const after = { ...before, functionPermissions: [{ function: "public.wipe_all", role: "anon" }] };
    const lines = describeChanges(before, after);
    expect(lines).toEqual([
      { role: "anon", table: "public.wipe_all", gained: ["execute"], lost: [], changed: [], text: "anon · public.wipe_all: + execute" },
      { role: "user", table: "public.wipe_all", gained: [], lost: ["execute"], changed: [], text: "user · public.wipe_all: - execute" }]);
    expect(widens(lines)).toBe(true);
    expect(widens(describeChanges(after, before))).toBe(true);
    expect(widens(describeChanges(before, { ...before, functionPermissions: [] }))).toBe(false);
  });

  test("an added or changed function entry is a changed rule; a removed one is not", async () => {
    const { describeChanges, widens } = await modelModule();
    const none = { tables: [] };
    const added = describeChanges(none, { tables: [], functions: [fn("public.wipe_all")] });
    expect(added).toMatchObject([{ table: "public.wipe_all", changed: ["function"], other: true, text: "function public.wipe_all: added" }]);
    expect(widens(added)).toBe(true);
    const base = { tables: [], functions: [fn("public.wipe_all")] };
    for (const edit of [{ inferPermissions: true }, { exposedAs: "QUERY" }, { sessionArgument: "session" }]) {
      const lines = describeChanges(base, { tables: [], functions: [fn("public.wipe_all", edit)] });
      expect(lines).toMatchObject([{ changed: ["function"], text: "function public.wipe_all: changed" }]);
      expect(widens(lines)).toBe(true);
    }
    const removed = describeChanges(base, none);
    expect(removed).toMatchObject([{ lost: ["function"], text: "function public.wipe_all: removed" }]);
    expect(widens(removed)).toBe(false);
  });

  test("any other top-level key that changes is a changed rule, so a grammar that grows still asks", async () => {
    const { describeChanges, widens } = await modelModule();
    const lines = describeChanges({ tables: [], format: 1, future: { a: 1 } }, { tables: [], format: 1, future: { a: 2 } });
    expect(lines).toMatchObject([{ changed: ["future"], other: true, text: "future changes" }]);
    expect(widens(lines)).toBe(true);
    expect(describeChanges({ tables: [], projectId: "a" }, { tables: [], projectId: "b" })).toHaveLength(1);
    expect(describeChanges({ tables: [], format: 1 }, { tables: [], format: 1 })).toEqual([]);
  });
});
