const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");
const { NEXT, NEXT_READ_ONLY } = require("../ports");

// What the old console could do, done again in the new one against a real engine and a real Postgres. Nothing here is
// answered by the test: a conflict is another writer saving first, a refused text is the engine refusing it.
const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const HEADER = { "X-Excalibase-Admin-Secret": SECRET };
const EXAMPLE = fs.readFileSync(path.join(__dirname, "..", "..", "deploy", "standalone", "metadata", "permissions.yaml"), "utf8");
const PLAIN = JSON.stringify({ format: 1, projectId: "my-app", tables: [] }, null, 2) + "\n";
const HISTORY_FILE = path.join(__dirname, "..", ".work", "permissions-next.history");
const SAVED = /^Saved\. The engine now serves version/;
const RED = "rgb(180, 35, 24)";
const AMBER = "rgb(181, 71, 8)";

const metadata = async (request, base = NEXT) => (await request.get(base + "/_admin/v1/metadata", { headers: HEADER })).json();

async function replaceDocument(request, text) {
  const { sourceVersion } = await metadata(request);
  const answer = await request.put(NEXT + "/_admin/v1/metadata",
    { headers: { ...HEADER, "If-Match": `"${sourceVersion}"`, "Content-Type": "text/plain" }, data: text });
  expect(answer.status()).toBe(200);
}

async function signIn(page, base = NEXT, hash = "#/permissions/document") {
  await page.goto(base + "/console" + hash);
  await page.getByLabel("Admin secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Console sections" })).toBeVisible();
}

/** What the engine itself reads from the served text: a YAML file is never read in the test. */
const treeOf = async (request, text) => (await (await request.post(NEXT + "/_admin/v1/parse",
  { headers: { ...HEADER, "Content-Type": "text/plain" }, data: text })).json()).tree;

const editor = (page) => page.locator(".cm-content");
async function editDraft(page, text) {
  await editor(page).click();
  await page.keyboard.press("Control+A");
  await page.keyboard.insertText(text);
}
/** The draft exactly as typed: what Export hands over. */
async function draftOf(page) {
  const [download] = await Promise.all([page.waitForEvent("download"), page.locator("#export").click()]);
  return fs.readFileSync(await download.path(), "utf8");
}
const tab = (page, name) => page.getByRole("tab", { name }).click();
const save = (page) => page.locator("#save").click();
const saved = (page) => page.getByText(SAVED);

test.beforeEach(async ({ request }) => {
  fs.rmSync(HISTORY_FILE, { force: true, recursive: true });
  if ((await metadata(request)).document !== EXAMPLE) {
    await replaceDocument(request, EXAMPLE);
  }
  fs.rmSync(HISTORY_FILE, { force: true, recursive: true });
});

test.describe("signing in and the shell", () => {
  test("a wrong secret is refused, the right one opens the console with the project in the header", async ({ page }) => {
    await page.goto(NEXT + "/console");
    await page.getByLabel("Admin secret").fill("not-the-secret");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert")).toContainText("not accepted");

    await page.getByLabel("Admin secret").fill(SECRET);
    await page.getByRole("button", { name: "Sign in" }).click();

    const nav = page.getByRole("navigation", { name: "Console sections" });
    await expect(nav.getByRole("link")).toHaveText(["Data", "Schema", "Permissions", "API explorer", "Replicas"]);
    const header = page.getByRole("banner");
    await expect(header).toContainText("my-app");
    await expect(header).toContainText("file");
    await expect(header).toContainText("writable");
  });

  test("each section opens from the nav, the address says where you are, and a reload stays there", async ({ page }) => {
    await signIn(page, NEXT, "");
    for (const [link, heading, hash] of [["Schema", "Schema", "#/schema"], ["Replicas", "Replicas", "#/replicas"],
      ["API explorer", "API explorer", "#/explorer"], ["Permissions", "Permissions", "#/permissions/document"], ["Data", "Data", "#/data"]]) {
      await page.getByRole("navigation", { name: "Console sections" }).getByRole("link", { name: link }).click();
      await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
      expect(new URL(page.url()).hash).toBe(hash);
    }
    await page.goto(NEXT + "/console#/schema");
    await expect(page.getByRole("heading", { level: 1, name: "Schema" })).toBeVisible();
  });

  test("an address that means nothing lands on Data, not on an error", async ({ page }) => {
    await signIn(page, NEXT, "#/no/such/place");

    await expect(page.getByRole("heading", { level: 1, name: "Data" })).toBeVisible();
  });

  test("signing out ends the session; the reload asks for the secret again", async ({ page }) => {
    await signIn(page);
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByLabel("Admin secret")).toBeVisible();

    await page.reload();

    await expect(page.getByLabel("Admin secret")).toBeVisible();
  });

  test("a reload keeps the session of the tab", async ({ page }) => {
    await signIn(page);

    await page.reload();

    await expect(page.getByRole("navigation", { name: "Console sections" })).toBeVisible();
  });
});

test.describe("Schema, Data and Replicas", () => {
  test("Schema lists the tables with their keys, and the view as a view", async ({ page }) => {
    await signIn(page, NEXT, "#/schema");

    const todos = page.getByRole("row", { name: /public\.todos/ });
    await expect(todos).toContainText("table");
    await expect(todos).toContainText("(key)");
  });

  test("Data lists the tables and shows the columns of the one picked", async ({ page }) => {
    await signIn(page, NEXT, "#/data");

    await page.getByRole("navigation", { name: "Tables" }).getByRole("link", { name: "public.todos" }).click();

    await expect(page.getByRole("heading", { level: 2, name: "public.todos" })).toBeVisible();
    expect(new URL(page.url()).hash).toBe("#/data/public.todos");
    await expect(page.getByRole("row", { name: /owner_id/ })).toBeVisible();
  });

  test("Replicas names this replica as ready, on the file source", async ({ page }) => {
    await signIn(page, NEXT, "#/replicas");

    const row = page.getByRole("row", { name: /this replica/ });
    await expect(row).toContainText("ready");
    await expect(row).toContainText("file");
  });
});

test.describe("the document", () => {
  test("it shows the served text; an edit is saved, served, and still there after a reload", async ({ page, request }) => {
    await signIn(page);
    await expect(editor(page)).toContainText("projectId: my-app");
    await expect(page.locator("#save")).toBeDisabled();
    expect(await draftOf(page)).toBe(EXAMPLE);

    const edited = EXAMPLE + "# edited in the browser\n";
    await editDraft(page, edited);
    await expect(page.locator("#save")).toBeEnabled();
    await save(page);

    await expect(saved(page)).toBeVisible();
    expect((await metadata(request)).document).toBe(edited);
    await page.reload();
    await expect(editor(page)).toContainText("# edited in the browser");
  });

  test("Discard edits puts the served text back", async ({ page }) => {
    await signIn(page);
    await editDraft(page, EXAMPLE + "# nope\n");

    await page.getByRole("button", { name: "Discard edits" }).click();

    await expect(page.locator("#save")).toBeDisabled();
    expect(await draftOf(page)).toBe(EXAMPLE);
  });

  test("a text the engine refuses is shown with the engine's errors, and the edit stays", async ({ page }) => {
    await signIn(page);
    const bad = EXAMPLE + "surprise: true\n";
    await editDraft(page, bad);

    await save(page);

    await expect(page.getByRole("alert")).toContainText("surprise");
    await expect(page.locator("dialog")).toHaveCount(0);
    expect(await draftOf(page)).toBe(bad);
  });

  test("Export gives the draft as a file", async ({ page }) => {
    await signIn(page);
    const [download] = await Promise.all([page.waitForEvent("download"), page.locator("#export").click()]);

    expect(download.suggestedFilename()).toBe("permissions.yaml");
    expect(fs.readFileSync(await download.path(), "utf8")).toBe(EXAMPLE);
  });

  test("another writer saving first is a conflict: theirs is shown, and the person picks", async ({ page, request }) => {
    await signIn(page);
    await replaceDocument(request, EXAMPLE + "# theirs\n");
    const mine = EXAMPLE + "# mine\n";
    await editDraft(page, mine);

    await save(page);

    const conflict = page.getByRole("alert");
    await expect(conflict).toContainText("Someone else saved first");
    await expect(page.locator("#theirs")).toContainText("# theirs");
    expect((await metadata(request)).document).toBe(EXAMPLE + "# theirs\n");
    await page.getByRole("button", { name: "Keep mine and overwrite theirs" }).click();
    await save(page);

    await expect(saved(page)).toBeVisible();
    expect((await metadata(request)).document).toBe(mine);
  });

  test("Use their version replaces the draft with what is served", async ({ page, request }) => {
    await signIn(page);
    await replaceDocument(request, EXAMPLE + "# theirs\n");
    await editDraft(page, EXAMPLE + "# mine\n");
    await save(page);

    await page.getByRole("button", { name: "Use their version" }).click();

    await expect(editor(page)).toContainText("# theirs");
    await expect(page.locator("#save")).toBeDisabled();
  });

  test("a save that the History could not record still goes ahead, and says so", async ({ page, request }) => {
    fs.mkdirSync(HISTORY_FILE);
    await signIn(page);
    await editDraft(page, EXAMPLE + "# note\n");

    await save(page);

    await expect(saved(page)).toBeVisible();
    await expect(page.getByRole("alert").filter({ hasText: "could not be added to History" })).toBeVisible();
    expect((await metadata(request)).document).toBe(EXAMPLE + "# note\n");
  });
});

async function tickAnonSelect(page) {
  await tab(page, "Grid");
  await page.locator("#new-role").fill("anon");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await page.getByLabel("select public.todos anon").check();
}

test.describe("the grid", () => {
  test("it has a column per role and a star on a rule written by hand", async ({ page }) => {
    await signIn(page);
    await tab(page, "Grid");

    await expect(page.getByLabel("select public.todos user")).toBeChecked();
    await expect(page.getByLabel("select public.todos anon")).toHaveCount(0);
    await expect(page.getByLabel("insert public.todos user")).toBeChecked();
    await expect(page.getByText("I*")).toBeVisible();
    await expect(page.getByText("S*")).toBeVisible();
  });

  test("a role that is not allowed is refused in words", async ({ page }) => {
    await signIn(page);
    await tab(page, "Grid");
    await page.locator("#new-role").fill("Service");
    await page.getByRole("button", { name: "Add", exact: true }).click();

    await expect(page.getByRole("alert")).toContainText("lowercase letters");
  });

  test("a save keeps the comments: adding access asks about the access, cancel writes nothing, save patches the file", async ({ page, request }) => {
    await signIn(page);
    await tickAnonSelect(page);

    await save(page);
    await expect(page.locator("#access-confirm")).toBeVisible();
    await expect(page.locator("#rewrite-warning")).toHaveCount(0);
    await page.locator("#rewrite-cancel").click();
    await expect(page.locator("dialog")).toHaveCount(0);
    expect((await metadata(request)).document).toBe(EXAMPLE);

    await save(page);
    await page.locator("#rewrite-save").click();
    await expect(saved(page)).toBeVisible();
    const written = (await metadata(request)).document;
    ["# yaml-language-server", "# must equal APP_PROJECT_ID", "# a user sees only their own rows", "# the owner is always the caller"]
      .forEach((comment) => expect(written).toContain(comment));
    expect(written.startsWith("# yaml-language-server")).toBe(true);
    expect((await treeOf(request, written)).tables.some((rule) => rule.role === "anon" && rule.select)).toBe(true);
    expect((await treeOf(request, written)).tables.find((rule) => rule.role === "user").select.filter)
      .toEqual({ owner_id: { _eq: "X-Excalibase-User-Id" } });
  });

  test("unticking an operation removes only that operation and keeps the other comments", async ({ page, request }) => {
    await signIn(page);
    await tab(page, "Grid");

    await page.getByLabel("delete public.todos user").uncheck();
    await save(page);

    await expect(saved(page)).toBeVisible();
    const written = (await metadata(request)).document;
    expect(written).toContain("# a user sees only their own rows");
    expect((await treeOf(request, written)).tables[0].delete).toBeUndefined();
    expect((await treeOf(request, written)).tables[0].update).toBeDefined();
  });

  test("a role the YAML 1.1 reading would turn into a boolean is still a role", async ({ page, request }) => {
    await signIn(page);
    await tab(page, "Grid");
    await page.locator("#new-role").fill("no");
    await page.getByRole("button", { name: "Add", exact: true }).click();

    await page.getByLabel("select public.todos no").check();
    await save(page);
    await page.locator("#rewrite-save").click();

    await expect(saved(page)).toBeVisible();
    expect((await treeOf(request, (await metadata(request)).document)).tables.map((rule) => rule.role)).toEqual(["user", "no"]);
  });

  test("the dialog starts on Cancel, is described by its text, and Escape cancels", async ({ page, request }) => {
    await signIn(page);
    await tickAnonSelect(page);
    await save(page);

    await expect(page.locator("#rewrite-cancel")).toBeFocused();
    await expect(page.locator("dialog")).toHaveAttribute("aria-describedby", "rewrite-text");
    await page.keyboard.press("Escape");
    await expect(page.locator("dialog")).toHaveCount(0);
    expect((await metadata(request)).document).toBe(EXAMPLE);
  });

  test("a double click on Save opens one warning and makes one save", async ({ page }) => {
    await signIn(page);
    await tickAnonSelect(page);

    await page.locator("#save").dblclick();

    await expect(page.locator("dialog")).toHaveCount(1);
    await page.locator("#rewrite-save").click();
    await expect(saved(page)).toBeVisible();
    await expect(page.getByText("Someone else saved first")).toHaveCount(0);
  });

  test("a plain file saved from the grid asks only about the access it widens, in red", async ({ page, request }) => {
    await replaceDocument(request, PLAIN);
    await signIn(page);
    await tickAnonSelect(page);
    await page.getByLabel("delete public.todos anon").check();

    await save(page);

    await expect(page.locator("#access-confirm")).toBeVisible();
    await expect(page.locator("#rewrite-warning")).toHaveCount(0);
    const gain = page.locator("#rewrite-changes li", { hasText: "anon · public.todos: + select, + delete" });
    await expect(gain).toHaveCSS("color", RED);
    await expect(page.locator("#rewrite-remember")).toHaveCount(0);
    await page.locator("#rewrite-cancel").click();
    expect((await metadata(request)).document).toBe(PLAIN);
    await save(page);
    await page.locator("#rewrite-save").click();
    await expect(saved(page)).toBeVisible();
    expect(JSON.parse((await metadata(request)).document).tables[0].delete).toBeTruthy();
  });

  test("an edit that only removes access saves without asking", async ({ page, request }) => {
    await replaceDocument(request, JSON.stringify({ format: 1, projectId: "my-app", tables: [{ table: "public.todos", role: "anon",
      select: { filter: {}, columns: "*" }, delete: { filter: {} } }] }, null, 2) + "\n");
    await signIn(page);
    await tab(page, "Grid");

    await page.getByLabel("delete public.todos anon").uncheck();
    await save(page);

    await expect(saved(page)).toBeVisible();
    await expect(page.locator("dialog")).toHaveCount(0);
  });

  test("a loosened filter typed in the document is listed in amber and asks", async ({ page, request }) => {
    await signIn(page);
    await editDraft(page, EXAMPLE.replace('filter: {"owner_id": {"_eq": "X-Excalibase-User-Id"}}', "filter: {}"));

    await save(page);

    await expect(page.locator("#rewrite-changes li", { hasText: "user · public.todos: ~ select" })).toHaveCSS("color", AMBER);
    await page.locator("#rewrite-cancel").click();
    expect((await metadata(request)).document).toBe(EXAMPLE);
  });
});

test.describe("the sample token", () => {
  test("claims are read in the browser, picked, and the settings are saved; the token goes nowhere", async ({ page, request }) => {
    const part = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
    const token = `${part({ alg: "HS256", typ: "JWT" })}.${part({ sub: "u-1", role: "user", app: { org: "acme" } })}.signature`;
    const sent = [];
    page.on("request", (outgoing) => sent.push(outgoing.postData() || ""));
    await signIn(page);
    await tab(page, "Sample token");

    await page.locator("#sample-token").fill(token);
    await page.locator("#decode").click();
    await expect(page.locator("#sample-token")).toHaveValue("");
    const row = (claim) => page.locator("tr", { has: page.getByText(claim, { exact: true }) });
    await row("role").getByRole("button", { name: "Role" }).click();
    await row("sub").getByRole("button", { name: "User id" }).click();
    await row("app.org").getByRole("button", { name: "Extra" }).click();
    await expect(page.getByText("X-Excalibase-org from app.org")).toBeVisible();
    await page.locator("#jwt-audience").fill("my-api");
    await page.locator("#jwt-secret").fill("env:E2E_JWT_SECRET");
    await page.locator("#apply-jwt").click();

    await expect(editor(page)).toContainText("userId: sub");
    await save(page);
    await page.locator("#rewrite-save").click();
    await expect(saved(page)).toBeVisible();
    const written = await treeOf(request, (await metadata(request)).document);
    expect((await metadata(request)).document).toContain("# a user sees only their own rows");
    expect(written.jwt).toMatchObject({ audience: "my-api", secret: "env:E2E_JWT_SECRET", claims: { role: "role", userId: "sub", extra: { org: "app.org" } } });
    expect(sent.join("\n")).not.toContain(token);
    expect(await page.evaluate(() => JSON.stringify({ ...window.sessionStorage, ...window.localStorage }))).not.toContain("eyJ");
  });

  test("a token that is not three parts is explained", async ({ page }) => {
    await signIn(page);
    await tab(page, "Sample token");

    await page.locator("#sample-token").fill("nonsense");
    await page.locator("#decode").click();

    await expect(page.getByRole("alert")).toContainText("three parts");
  });
});

test.describe("History", () => {
  test("two saves, two entries, a diff, Restore brings the comment back, and the restore is in the history", async ({ page, request }) => {
    await signIn(page);
    await editDraft(page, EXAMPLE + "# note one\n");
    await save(page);
    await expect(saved(page)).toBeVisible();
    await tickAnonSelect(page);
    await save(page);
    await page.locator("#rewrite-save").click();
    await expect(saved(page)).toBeVisible();

    await tab(page, "History");
    await expect(page.locator("#history-list tbody tr")).toHaveCount(2);
    await page.locator("#history-list tbody tr").first().getByRole("button", { name: "View" }).click();
    await expect(page.locator("#diff .line.del").filter({ hasText: "# note one" })).toHaveCount(1);
    await expect(page.locator("#diff .line.add").first()).toBeVisible();

    await page.locator("#restore").click();
    await expect(page.locator("#restore-confirm")).toContainText("Restore this version");
    await page.locator("#restore-yes").click();

    await expect(page.getByText(/^Restored\. The engine now serves version/)).toBeVisible();
    expect((await metadata(request)).document).toBe(EXAMPLE + "# note one\n");
    await expect(page.locator("#history-list tbody tr")).toHaveCount(3);
    await tab(page, "Document");
    await expect(editor(page)).toContainText("# note one");
  });

  test("cancelling the restore confirm restores nothing", async ({ page, request }) => {
    await signIn(page);
    await editDraft(page, EXAMPLE + "# note one\n");
    await save(page);
    await expect(saved(page)).toBeVisible();
    await tab(page, "History");
    await page.locator("#history-list tbody tr").first().getByRole("button", { name: "View" }).click();

    await page.locator("#restore").click();
    await page.locator("#restore-no").click();

    await expect(page.locator("#restore-confirm")).toHaveCount(0);
    expect((await metadata(request)).document).toBe(EXAMPLE + "# note one\n");
  });

  test("an entry that no longer validates is refused with the reason, and the document stays", async ({ page, request }) => {
    await signIn(page);
    await editDraft(page, EXAMPLE + "# note one\n");
    await save(page);
    await expect(saved(page)).toBeVisible();
    const bad = EXAMPLE + "surprise: true\n";
    const hash = require("node:crypto").createHash("sha256").update(bad).digest("hex");
    fs.appendFileSync(HISTORY_FILE, JSON.stringify({ id: Date.now() + 5_000_000, at: "2026-11-20T10:00:00Z", hash, text: bad }) + "\n");
    await tab(page, "History");
    await page.locator("#history-list tbody tr").first().getByRole("button", { name: "View" }).click();

    await page.locator("#restore").click();
    await page.locator("#restore-yes").click();

    await expect(page.getByRole("alert")).toContainText("surprise");
    expect((await metadata(request)).document).toBe(EXAMPLE + "# note one\n");
  });

  test("restoring a version that re-grants access shows the grant in red", async ({ page, request }) => {
    await replaceDocument(request, JSON.stringify({ format: 1, projectId: "my-app", tables: [{ table: "public.todos", role: "anon", select: { filter: {}, columns: "*" } }] }, null, 2) + "\n");
    await replaceDocument(request, PLAIN);
    await signIn(page);
    await tab(page, "History");
    await page.locator("#history-list tbody tr").first().getByRole("button", { name: "View" }).click();

    await page.locator("#restore").click();

    await expect(page.locator("#restore-changes li", { hasText: "anon · public.todos: + select" })).toHaveCSS("color", RED);
    await expect(page.locator("#restore-confirm")).toContainText("changes who can access your data");
    await page.locator("#restore-no").click();
  });

  test("an empty history says so", async ({ page }) => {
    await signIn(page);
    await tab(page, "History");

    await expect(page.getByText("Nothing has been replaced yet.")).toBeVisible();
  });

  test("a history that cannot be read says so instead of showing an empty list", async ({ page }) => {
    fs.mkdirSync(HISTORY_FILE);
    await signIn(page);
    await tab(page, "History");

    await expect(page.getByText("The history could not be read")).toBeVisible();
    await expect(page.getByText("Nothing has been replaced yet.")).toHaveCount(0);
  });

  test("a very large entry is not drawn: the page stays responsive and offers both versions as files", async ({ page }) => {
    const big = Array.from({ length: 40_000 }, (_, i) => "# line " + i).join("\n") + "\n";
    const hash = require("node:crypto").createHash("sha256").update(big).digest("hex");
    fs.writeFileSync(HISTORY_FILE, JSON.stringify({ id: Date.now(), at: "2026-11-20T10:00:00Z", hash, text: big }) + "\n");
    await signIn(page);
    await tab(page, "History");

    await page.locator("#history-list tbody tr").first().getByRole("button", { name: "View" }).click();

    await expect(page.getByText("This diff is too large to show here")).toBeVisible({ timeout: 5000 });
    await expect(page.locator("#diff .line")).toHaveCount(0);
    const [file] = await Promise.all([page.waitForEvent("download"), page.locator("#save-old").click()]);
    expect(file.suggestedFilename()).toMatch(/^permissions-old\.(yaml|json)$/);
  });
});

test.describe("Validate", () => {
  test("a good text reports its counts, a bad one the engine's reasons", async ({ page }) => {
    await signIn(page);
    await tab(page, "Validate");

    await page.getByLabel("Text to check").fill(EXAMPLE);
    await page.getByRole("button", { name: "Validate", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Valid:");

    await page.getByLabel("Text to check").fill(EXAMPLE + "surprise: true\n");
    await page.getByRole("button", { name: "Validate", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("surprise");
  });
});

test.describe("a read-only source", () => {
  test("cannot be saved from the console; Export takes a copy; the engine refuses the write too", async ({ page, request }) => {
    await signIn(page, NEXT_READ_ONLY);

    await expect(page.getByRole("banner")).toContainText("read-only");
    await expect(page.locator("#save")).toBeDisabled();
    await editDraft(page, EXAMPLE + "# nope\n");
    await expect(page.locator("#save")).toBeDisabled();
    await expect(page.getByText("Read-only source", { exact: false })).toBeVisible();
    expect(await draftOf(page)).toBe(EXAMPLE + "# nope\n");

    const answer = await request.put(NEXT_READ_ONLY + "/_admin/v1/metadata",
      { headers: { ...HEADER, "If-Match": "\"x\"", "Content-Type": "text/plain" }, data: EXAMPLE });
    expect(answer.status()).toBe(405);
  });

  test("History says it is for the file source", async ({ page }) => {
    await signIn(page, NEXT_READ_ONLY, "#/permissions/history");

    await expect(page.getByText("History is available for the file source.")).toBeVisible();
  });
});
