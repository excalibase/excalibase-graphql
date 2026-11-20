const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");

const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const WRITABLE = "http://localhost:10001";
const READ_ONLY = "http://localhost:10011";
const HEADER = { "X-Excalibase-Admin-Secret": SECRET };
const EXAMPLE = fs.readFileSync(path.join(__dirname, "..", "..", "deploy", "standalone", "metadata", "permissions.yaml"), "utf8");
const MARKUP = `<img src=x onerror="window.__pwned=1"><script>window.__pwned=1</script>`;

const metadata = async (request, base = WRITABLE) => (await request.get(base + "/_admin/v1/metadata", { headers: HEADER })).json();

async function replaceDocument(request, text) {
  const { sourceVersion } = await metadata(request);
  const answer = await request.put(WRITABLE + "/_admin/v1/metadata",
    { headers: { ...HEADER, "If-Match": `"${sourceVersion}"`, "Content-Type": "text/plain" }, data: text });
  expect(answer.status()).toBe(200);
}

async function signIn(page, base = WRITABLE) {
  await page.goto(base + "/console");
  await page.locator("#secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#draft")).toBeVisible();
}

const editDraft = (page, text) => page.locator("#draft").fill(text);

test.beforeEach(async ({ request }) => {
  const { document: served } = await metadata(request);
  if (served !== EXAMPLE) {
    await replaceDocument(request, EXAMPLE);
  }
});

test("a wrong secret is refused, the right one shows the served document", async ({ page }) => {
  await page.goto(WRITABLE + "/console");
  await page.locator("#secret").fill("not-the-secret");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toContainText("not accepted");

  await signIn(page);
  await expect(page.locator("#draft")).toHaveValue(EXAMPLE);
  await expect(page.getByText("writable", { exact: true })).toBeVisible();
  await expect(page.locator("#save")).toBeDisabled();
});

test("an edit is saved, served, and still there after a reload", async ({ page, request }) => {
  await signIn(page);
  const edited = EXAMPLE + "# edited in the browser\n";
  await editDraft(page, edited);
  await expect(page.locator("#save")).toBeEnabled();
  await page.locator("#save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  expect((await metadata(request)).document).toBe(edited);
  await page.reload();
  await expect(page.locator("#draft")).toHaveValue(edited);
});

test("a stale save is a 409 that shows their version and never overwrites it", async ({ page, request }) => {
  await signIn(page);
  await editDraft(page, EXAMPLE + "# mine\n");
  const theirs = EXAMPLE + "# theirs\n";
  await replaceDocument(request, theirs);

  await page.locator("#save").click();

  await expect(page.getByRole("alert")).toContainText("Someone else saved first");
  await expect(page.locator("#theirs")).toContainText("# theirs");
  expect((await metadata(request)).document).toBe(theirs);

  await page.getByRole("button", { name: "Use their version" }).click();
  await expect(page.locator("#draft")).toHaveValue(theirs);
  await expect(page.locator("#save")).toBeDisabled();
});

test("overwriting after a conflict is an explicit second choice", async ({ page, request }) => {
  await signIn(page);
  const mine = EXAMPLE + "# mine\n";
  await editDraft(page, mine);
  await replaceDocument(request, EXAMPLE + "# theirs\n");
  await page.locator("#save").click();
  await expect(page.locator("#theirs")).toBeVisible();

  await page.getByRole("button", { name: "Keep mine and overwrite theirs" }).click();
  await page.locator("#save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  expect((await metadata(request)).document).toBe(mine);
});

test("an invalid document shows the engine's errors and saves nothing", async ({ page, request }) => {
  await signIn(page);
  await editDraft(page, "format: 1\nprojectId: [unclosed\n");
  await page.locator("#save").click();

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("not valid");
  await expect(alert.getByRole("listitem")).not.toHaveCount(0);
  expect((await metadata(request)).document).toBe(EXAMPLE);

  await page.getByRole("tab", { name: "Validate" }).click();
  await page.locator("#candidate").fill("format: 1\nprojectId: elsewhere\n");
  await page.getByRole("button", { name: "Validate", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Not valid:" })).toBeVisible();
});

test("markup in a served document is shown as text and never runs", async ({ page, request }) => {
  const hostile = EXAMPLE + `# ${MARKUP}\n`;
  await replaceDocument(request, hostile);
  await signIn(page);
  await expect(page.locator("#draft")).toHaveValue(hostile);

  await editDraft(page, EXAMPLE + "# mine\n");
  await replaceDocument(request, EXAMPLE + `# ${MARKUP} again\n`);
  await page.locator("#save").click();

  await expect(page.locator("#theirs")).toContainText(MARKUP);
  await expect(page.locator("#theirs img, #theirs script, main img, main script")).toHaveCount(0);
  expect(await page.evaluate(() => window.__pwned)).toBeUndefined();
});

test("the grid writes a rule for a ticked box, keeps hand-written rules, and adds a role", async ({ page, request }) => {
  await signIn(page);
  await page.getByRole("tab", { name: "Grid" }).click();
  const userSelect = page.getByLabel("select public.todos user");
  await expect(userSelect).toBeChecked();
  await expect(page.getByTitle("select: a custom rule, edit it as text")).toBeVisible();

  await page.locator("#new-role").fill("anon");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await page.getByLabel("select public.todos anon").check();
  await page.locator("#new-role").fill("Bad Role");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("lowercase");
  await page.locator("#new-role").fill("editor");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByRole("columnheader", { name: "editor" })).toBeVisible();
  await page.locator("#save").click();
  await page.locator("#rewrite-save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  const saved = JSON.parse((await metadata(request)).document);
  const anon = saved.tables.find((rule) => rule.role === "anon");
  expect(anon).toEqual({ table: "public.todos", role: "anon", select: { filter: {}, columns: "*" } });
  expect(saved.tables.find((rule) => rule.role === "user").select.filter).toEqual({ owner_id: { _eq: "X-Excalibase-User-Id" } });
});

test("a sample token is read in the browser, its claims are picked, and the settings save", async ({ page, request }) => {
  const part = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const token = `${part({ alg: "HS256", typ: "JWT" })}.${part({ sub: "u-1", role: "user", app: { org: "acme" } })}.signature`;
  const sent = [];
  page.on("request", (outgoing) => sent.push(outgoing.postData() || ""));
  await signIn(page);
  await page.getByRole("tab", { name: "Sample token" }).click();

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

  await expect(page.locator("#draft")).toHaveValue(/"jwt"[\s\S]*"userId": "sub"[\s\S]*"org": "app.org"/);
  await page.locator("#save").click();
  await page.locator("#rewrite-save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  const saved = JSON.parse((await metadata(request)).document);
  expect(saved.jwt).toMatchObject({ audience: "my-api", secret: "env:E2E_JWT_SECRET", claims: { role: "role", userId: "sub", extra: { org: "app.org" } } });
  expect(sent.join("\n")).not.toContain(token);
  expect(await page.evaluate(() => JSON.stringify({ ...window.sessionStorage, ...window.localStorage }))).not.toContain("eyJ");
});

test("Export downloads the document as a file, under the strict policy", async ({ page }) => {
  const violations = [];
  page.on("console", (message) => { if (/Content Security Policy|Trusted Type/i.test(message.text())) { violations.push(message.text()); } });
  await signIn(page);

  const [download] = await Promise.all([page.waitForEvent("download"), page.locator("#export").click()]);

  expect(download.suggestedFilename()).toBe("permissions.yaml");
  expect(fs.readFileSync(await download.path(), "utf8")).toBe(EXAMPLE);
  expect(violations).toEqual([]);
});

test("a read-only source cannot be saved from the console, and Export is how to take it", async ({ page, request }) => {
  await signIn(page, READ_ONLY);

  await expect(page.getByText("read-only", { exact: true })).toBeVisible();
  await expect(page.locator("#save")).toBeDisabled();
  await editDraft(page, EXAMPLE + "# nope\n");
  await expect(page.locator("#save")).toBeDisabled();
  await expect(page.getByRole("status").filter({ hasText: "Read-only source" })).toBeVisible();
  const [download] = await Promise.all([page.waitForEvent("download"), page.locator("#export").click()]);
  expect(fs.readFileSync(await download.path(), "utf8")).toBe(EXAMPLE + "# nope\n");

  const answer = await request.put(READ_ONLY + "/_admin/v1/metadata",
    { headers: { ...HEADER, "If-Match": "\"x\"", "Content-Type": "text/plain" }, data: EXAMPLE });
  expect(answer.status()).toBe(405);
});

// The engine answers these only on a failing disk or a refused reload, which a test cannot cause on purpose, so the
// answer is staged at the network and the page's explanation of each is checked.
for (const [status, error, expected] of [
  [500, "reload_refused", "could not load it. The last good document is still served"],
  [502, "write_failed", "could not be written, so nothing was saved"],
  [428, "if_match_required", "did not send the version"],
  [405, "source_read_only", "This source is read-only"]
]) {
  test(`a ${status} answer to a save is explained, and the edit is kept`, async ({ page }) => {
    await signIn(page);
    await page.route("**/_admin/v1/metadata", (route) => route.request().method() === "PUT"
      ? route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ error }) })
      : route.fallback());
    const edited = EXAMPLE + "# kept\n";
    await editDraft(page, edited);
    await page.locator("#save").click();

    await expect(page.getByRole("alert")).toContainText(expected);
    await expect(page.locator("#draft")).toHaveValue(edited);
  });
}

async function stageConflict(page, currentVersion) {
  let first = true;
  const puts = [];
  await page.route("**/_admin/v1/metadata", (route) => {
    const method = route.request().method();
    if (method !== "PUT") {
      return route.fallback();
    }
    puts.push(route.request().headers()["if-match"]);
    if (first) {
      first = false;
      return route.fulfill({ status: 409, contentType: "application/json",
        body: JSON.stringify({ error: "version_conflict", currentVersion }) });
    }
    return route.fallback();
  });
  return puts;
}

test("a 409 whose other version cannot be fetched still offers a working overwrite", async ({ page, request }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await signIn(page);
  const { sourceVersion } = await metadata(request);
  const puts = await stageConflict(page, sourceVersion);
  let aborted = false;
  await page.route("**/_admin/v1/metadata", (route) => {
    if (route.request().method() === "GET" && !aborted) {
      aborted = true;
      return route.abort();
    }
    return route.fallback();
  });
  const mine = EXAMPLE + "# mine\n";
  await editDraft(page, mine);
  await page.locator("#save").click();

  await expect(page.getByRole("alert")).toContainText("Someone else saved first");
  await expect(page.getByRole("alert")).toContainText("could not be shown");
  await page.getByRole("button", { name: "Keep mine and overwrite theirs" }).click();
  await page.locator("#save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  expect((await metadata(request)).document).toBe(mine);
  expect(puts[1]).toBe(`"${sourceVersion}"`);
  expect(errors).toEqual([]);
});

test("a replica that has not caught up is named, and the overwrite uses the version the 409 gave", async ({ page, request }) => {
  await signIn(page);
  const puts = await stageConflict(page, "newer-than-this-replica");
  await editDraft(page, EXAMPLE + "# mine\n");
  await page.locator("#save").click();

  await expect(page.getByRole("alert")).toContainText("not caught up");
  await page.getByRole("button", { name: "Keep mine and overwrite theirs" }).click();
  await page.locator("#save").click();

  await expect.poll(() => puts.length).toBe(2);
  expect(puts[1]).toBe('"newer-than-this-replica"');
});

const WARNING = "This save rewrites permissions.yaml. Comments and your own layout will be lost.";

async function tickAnonSelect(page) {
  await page.getByRole("tab", { name: "Grid" }).click();
  await page.locator("#new-role").fill("anon");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await page.getByLabel("select public.todos anon").check();
}

test("a grid save over a file with comments warns first; cancel writes nothing, save rewrites", async ({ page, request }) => {
  await signIn(page);
  await tickAnonSelect(page);

  await page.locator("#save").click();
  await expect(page.locator("#rewrite-warning")).toContainText(WARNING);
  await page.locator("#rewrite-cancel").click();
  await expect(page.locator("#rewrite-warning")).toHaveCount(0);
  expect((await metadata(request)).document).toBe(EXAMPLE);

  await page.locator("#save").click();
  await page.locator("#rewrite-save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  const saved = (await metadata(request)).document;
  expect(saved).not.toContain("#");
  expect(JSON.parse(saved).tables.some((rule) => rule.role === "anon")).toBe(true);
});

test("a grid save over a file already in the grid's own form does not warn", async ({ page }) => {
  await signIn(page);
  await tickAnonSelect(page);
  await page.locator("#save").click();
  await page.locator("#rewrite-save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();

  await page.getByLabel("insert public.todos anon").check();
  await page.locator("#save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  await expect(page.locator("#rewrite-warning")).toHaveCount(0);
});

test("a plain text edit never shows the grid warning", async ({ page }) => {
  await signIn(page);
  await editDraft(page, EXAMPLE + "# typed\n");
  await page.locator("#save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  await expect(page.locator("#rewrite-warning")).toHaveCount(0);
});

test("Don't ask again skips the warning for the session, and signing out brings it back", async ({ page }) => {
  await signIn(page);
  await tickAnonSelect(page);
  await page.locator("#save").click();
  await page.getByLabel("Don't ask again this session").check();
  await page.locator("#rewrite-save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();

  await page.getByRole("tab", { name: "Document" }).click();
  await editDraft(page, EXAMPLE);
  await page.getByRole("tab", { name: "Grid" }).click();
  await page.getByLabel("delete public.todos anon").check();
  await page.locator("#save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  await expect(page.locator("#rewrite-warning")).toHaveCount(0);

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.locator("#secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#draft")).toBeVisible();
  await editDraft(page, EXAMPLE);
  await page.getByRole("tab", { name: "Grid" }).click();
  await page.getByLabel("insert public.todos anon").check();
  await page.locator("#save").click();
  await expect(page.locator("#rewrite-warning")).toBeVisible();
});

const touchDraft = async (page) => editDraft(page, (await page.locator("#draft").inputValue()) + " ");

test("typing on the Document tab after a grid edit still warns, and cancel keeps the comments", async ({ page, request }) => {
  await signIn(page);
  await tickAnonSelect(page);
  await page.getByRole("tab", { name: "Document" }).click();
  await touchDraft(page);

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toContainText(WARNING);
  await page.locator("#rewrite-cancel").click();
  expect((await metadata(request)).document).toBe(EXAMPLE);
});

test("typing on the Document tab after a token edit still warns", async ({ page, request }) => {
  await signIn(page);
  await page.getByRole("tab", { name: "Sample token" }).click();
  await page.locator("#jwt-audience").fill("my-api");
  await page.locator("#apply-jwt").click();
  await expect(page.locator("#draft")).toBeVisible();
  await touchDraft(page);

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toContainText(WARNING);
  await page.locator("#rewrite-cancel").click();
  expect((await metadata(request)).document).toBe(EXAMPLE);
});

test("comments pasted on the Document tab before a grid edit are warned about too", async ({ page, request }) => {
  await replaceDocument(request, PLAIN);
  await signIn(page);
  await editDraft(page, EXAMPLE);
  await tickAnonSelect(page);

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toBeVisible();
});

test("a grid save whose current text cannot be read is warned about, not saved silently", async ({ page, request }) => {
  await signIn(page);
  await tickAnonSelect(page);
  await page.route("**/_admin/v1/parse", (route) => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ valid: false, errors: ["unreadable"] }) }));

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toBeVisible();
  await page.locator("#rewrite-cancel").click();
  expect((await metadata(request)).document).toBe(EXAMPLE);
});

test("a double click on Save opens one warning and makes one save", async ({ page }) => {
  await signIn(page);
  await tickAnonSelect(page);

  await page.locator("#save").dblclick();

  await expect(page.locator("dialog")).toHaveCount(1);
  await page.locator("#rewrite-save").click();
  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  await expect(page.getByText("Someone else saved first")).toHaveCount(0);
});

test("the warning starts on Cancel, is described by its text, and Escape cancels", async ({ page, request }) => {
  await signIn(page);
  await tickAnonSelect(page);
  await page.locator("#save").click();

  await expect(page.locator("#rewrite-cancel")).toBeFocused();
  await expect(page.locator("#rewrite-warning")).toHaveAttribute("aria-describedby", "rewrite-text");
  await page.keyboard.press("Escape");
  await expect(page.locator("dialog")).toHaveCount(0);
  expect((await metadata(request)).document).toBe(EXAMPLE);
  await page.locator("#save").click();
  await expect(page.locator("#rewrite-warning")).toBeVisible();
});

const PLAIN = JSON.stringify({ format: 1, projectId: "my-app", tables: [] }, null, 2) + "\n";
// A JSON-looking text is read as JSON, which has no comments, so the typed text is YAML.
const addComment = (page) => editDraft(page, "# keep me\nformat: 1\nprojectId: my-app\ntables: []\n");

test("a comment typed between two grid edits is warned about before the save", async ({ page, request }) => {
  await replaceDocument(request, PLAIN);
  await signIn(page);
  await tickAnonSelect(page);
  await page.getByRole("tab", { name: "Document" }).click();
  await addComment(page);
  await page.getByRole("tab", { name: "Grid" }).click();
  await page.getByLabel("insert public.todos anon").check();

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toBeVisible();
  await page.locator("#rewrite-cancel").click();
  expect((await metadata(request)).document).toBe(PLAIN);
});

test("a comment typed between two token edits is warned about before the save", async ({ page, request }) => {
  await replaceDocument(request, PLAIN);
  await signIn(page);
  await page.getByRole("tab", { name: "Sample token" }).click();
  await page.locator("#jwt-audience").fill("first");
  await page.locator("#apply-jwt").click();
  await expect(page.locator("#draft")).toBeVisible();
  await addComment(page);
  await page.getByRole("tab", { name: "Sample token" }).click();
  await page.locator("#jwt-audience").fill("second");
  await page.locator("#apply-jwt").click();
  await expect(page.locator("#draft")).toBeVisible();

  await page.locator("#save").click();

  await expect(page.locator("#rewrite-warning")).toBeVisible();
  await page.locator("#rewrite-cancel").click();
  expect((await metadata(request)).document).toBe(PLAIN);
});

test("grid edits on a plain file, with no comment typed in between, save without a warning", async ({ page, request }) => {
  await replaceDocument(request, PLAIN);
  await signIn(page);
  await tickAnonSelect(page);
  await page.getByLabel("insert public.todos anon").check();

  await page.locator("#save").click();

  await expect(page.getByText(/^Saved\. The engine now serves version/)).toBeVisible();
  await expect(page.locator("#rewrite-warning")).toHaveCount(0);
});
