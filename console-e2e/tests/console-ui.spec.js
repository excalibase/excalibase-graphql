const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");
const { NEXT } = require("../ports");

// The default console, driven the way a person drives it, against a real engine on Postgres: the engine reads the
// permissions file the page saves, so a save is checked by asking the engine, not the page.
const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const ADMIN = NEXT + "/_admin/v1";
const AUTH = { "X-Excalibase-Admin-Secret": SECRET };
const EXAMPLE = fs.readFileSync(path.join(__dirname, "..", "..", "deploy", "standalone", "metadata", "permissions.yaml"), "utf8");
const SHOP = EXAMPLE.slice(0, EXAMPLE.indexOf("tables:")) + `tables:
  - table: public.orders
    role: user
    select: {filter: {}, columns: ["id", "customer_id", "status", "total"]}
`;
const PAGES = ["data", "schema/public.orders", "permissions/%40user", "permissions/yaml", "permissions/token",
  "permissions/history", "explorer", "replicas", "settings"];

const watchViolations = (page) => page.addInitScript(() => {
  window.__violations = [];
  document.addEventListener("securitypolicyviolation", (event) => {
    window.__violations.push(`${event.violatedDirective} ${event.blockedURI || event.sample || ""}`.trim());
  });
});

async function metadata(request) {
  return (await request.get(ADMIN + "/metadata", { headers: AUTH })).json();
}

/** What the engine reads from the served text: the test never reads YAML itself. */
async function served(request) {
  const text = (await metadata(request)).document;
  const answer = await (await request.post(ADMIN + "/parse", { headers: { ...AUTH, "Content-Type": "text/plain" }, data: text })).json();
  expect(answer.valid, JSON.stringify(answer)).toBe(true);
  return answer.tree;
}

async function putDocument(request, text) {
  const current = await metadata(request);
  const saved = await request.put(ADMIN + "/metadata", { headers: { ...AUTH, "Content-Type": "text/plain",
    "If-Match": `"${current.sourceVersion}"` }, data: text });
  expect(saved.status(), await saved.text()).toBe(200);
}

async function signIn(page, hash = "") {
  await page.goto(NEXT + "/console");
  await page.getByLabel("Admin secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Console" })).toBeVisible();
  if (hash) {
    await page.evaluate((value) => { window.location.hash = value; }, hash);
  }
}

const saveButton = (page) => page.getByRole("button", { name: "Save and apply" }).first();

test.beforeEach(async ({ request }) => {
  await putDocument(request, EXAMPLE);
});

test("signing in: a wrong secret is said so in plain words, the right one opens the console, a reload keeps it", async ({ page }) => {
  await page.goto(NEXT + "/console");

  await page.getByLabel("Admin secret").fill("not-the-secret-not-the-secret-not-the-secret");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toHaveText("That secret was not accepted.");

  await page.getByLabel("Admin secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Console" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("navigation", { name: "Console" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByLabel("Admin secret")).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Admin secret")).toBeVisible();
});

test("every page loads under the real policy with nothing blocked and nothing logged", async ({ page }) => {
  await watchViolations(page);
  const logged = [];
  page.on("console", (message) => message.type() === "error" && logged.push(message.text()));
  await signIn(page);

  for (const target of PAGES) {
    await page.evaluate((value) => { window.location.hash = value; }, `#/${target}`);
    await expect(page.getByRole("main")).toBeVisible();
    await expect(page.getByRole("main").getByRole("heading").first()).toBeVisible();
  }

  expect(await page.evaluate(() => window.__violations)).toEqual([]);
  expect(logged).toEqual([]);
});

test("the menu lists the pages of the design, marks the open one, and lists the tables beside the data pages", async ({ page }) => {
  await signIn(page, "#/schema");
  const menu = page.getByRole("navigation", { name: "Console" });

  await expect(menu.getByRole("link").filter({ hasText: /^(Data|Schema|Permissions|API explorer|Replicas|Settings)/ })).toHaveText(
    [/Data/, /Schema/, /Permissions/, /API explorer/, /Replicas/, /Settings/]);
  await expect(menu.getByRole("link", { name: "Schema" })).toHaveAttribute("aria-current", "page");
  for (const table of ["customers", "order_items", "orders", "todos"]) {
    await expect(menu.getByRole("link", { name: table, exact: true })).toBeVisible();
  }
});

test("a role's grid: click a cell, count the change, confirm the wider access, and the engine serves it", async ({ page, request }) => {
  await signIn(page, "#/permissions/%40anon");
  await expect(page.getByRole("heading", { name: "What can “anon” do?" })).toBeVisible();
  await expect(saveButton(page)).toBeDisabled();

  await page.getByRole("button", { name: /anon can delete todos: None/ }).click();
  await expect(page.getByText("1 unsaved change")).toBeVisible();
  await saveButton(page).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/changes who can reach your data/)).toBeVisible();
  await expect(dialog.getByRole("list", { name: "What this changes" })).toContainText("anon · public.todos: + delete");
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await dialog.getByRole("button", { name: "Save and apply" }).click();

  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();
  expect((await served(request)).tables.find((rule) => rule.table === "public.todos" && rule.role === "anon")).toHaveProperty("delete");
  const text = (await metadata(request)).document;
  ["# yaml-language-server", "# must equal APP_PROJECT_ID", "# a user sees only their own rows"].forEach((comment) => expect(text).toContain(comment));
  await page.reload();
  await expect(page.getByRole("button", { name: /anon can delete todos: All rows/ })).toBeVisible();
});

test("a grid change never asks about comments: the file keeps them, and only the access is asked", async ({ page, request }) => {
  await signIn(page, "#/permissions/%40anon");
  await page.getByRole("button", { name: /anon can delete todos: None/ }).click();
  await saveButton(page).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/changes who can reach your data/)).toBeVisible();
  await expect(dialog.getByText(/Comments and your own layout will be lost/)).toHaveCount(0);
  await dialog.getByRole("button", { name: "Save and apply" }).click();
  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();

  const text = (await metadata(request)).document;
  expect(text.startsWith("# yaml-language-server")).toBe(true);
  expect(text).not.toMatch(/^\s*\{/);
  const roles = (await served(request)).tables.map((rule) => rule.role);
  expect(roles).toEqual(["user", "anon"]);
});

test("a role named like a YAML 1.1 boolean is saved as text, not as true", async ({ page, request }) => {
  await signIn(page, "#/permissions");
  await page.getByLabel("Role name").fill("no");
  await page.getByRole("button", { name: "Add role" }).click();
  await page.getByRole("button", { name: /no can read todos: None/ }).click();
  await saveButton(page).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save and apply" }).click();
  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();

  expect((await served(request)).tables.map((rule) => rule.role)).toEqual(["user", "no"]);
});

test("cancelling the question, or pressing Escape, saves nothing and puts focus back", async ({ page, request }) => {
  await signIn(page, "#/permissions/%40anon");
  const before = (await metadata(request)).sourceVersion;
  await page.getByRole("button", { name: /anon can delete todos: None/ }).click();

  await saveButton(page).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");

  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(saveButton(page)).toBeFocused();
  expect((await metadata(request)).sourceVersion).toBe(before);
});

test("a rule written by hand shows as some rows and some columns, and the role's page lists its tables", async ({ page, request }) => {
  await putDocument(request, SHOP);
  await signIn(page, "#/permissions/%40user");

  await expect(page.getByRole("button", { name: /user can read orders: Some columns/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /user can read todos: None/ })).toBeVisible();
  await expect(page.getByRole("group", { name: "ROLES" }).getByRole("link", { name: /user/ })).toContainText("1 table");
});

test("a new role gets a column of its own, starting with no access", async ({ page }) => {
  await signIn(page, "#/permissions");

  await page.getByRole("button", { name: "+ New role" }).click();
  await page.getByLabel("Role name").fill("Editor Role");
  await page.getByRole("button", { name: "Add role" }).click();
  await expect(page.getByRole("alert")).toContainText("lowercase letters");
  await page.getByLabel("Role name").fill("editor");
  await page.keyboard.press("Enter");

  await expect(page.getByRole("heading", { name: "What can “editor” do?" })).toBeVisible();
  await expect(page.getByRole("button", { name: /editor can read todos: None/ })).toBeVisible();
});

test("the YAML view keeps comments: an edit made there is saved as typed, with no warning", async ({ page, request }) => {
  await signIn(page, "#/permissions/yaml");
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type("\n# checked by the browser test\n");

  await expect(page.getByText("You have unsaved edits.")).toBeVisible();
  await saveButton(page).click();

  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();
  expect((await metadata(request)).document).toContain("# checked by the browser test");
});

test("the YAML view says what is wrong with a text, and a refused save lists the engine's reasons", async ({ page, request }) => {
  await signIn(page, "#/permissions/yaml");
  const before = (await metadata(request)).document;
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+A");
  await page.keyboard.type("format: 1\nprojectId: someone-else\ntables: []\n");

  await page.getByRole("button", { name: "Check this text" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "This text is not valid" })).toBeVisible();
  await saveButton(page).click();

  await expect(page.getByText("Not saved: the document is not valid.")).toBeVisible();
  expect((await metadata(request)).document).toBe(before);
});

test("a save that lost to someone else is explained, shows their version, and overwrites only when chosen", async ({ page, request }) => {
  await signIn(page, "#/permissions/yaml");
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type("\n# mine\n");
  await putDocument(request, EXAMPLE + "\n# theirs\n");

  await saveButton(page).click();

  await expect(page.getByText(/Someone else saved first/)).toBeVisible();
  await expect(page.locator("#theirs")).toContainText("# theirs");
  expect((await metadata(request)).document).not.toContain("# mine");
  await page.getByRole("button", { name: "Keep mine and replace theirs" }).click();
  await saveButton(page).click();
  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();
  expect((await metadata(request)).document).toContain("# mine");
});

test("the sample token: paste, read the claims, pick the role and the user id, apply, save", async ({ page, request }) => {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const token = `${encode({ alg: "HS256" })}.${encode({ role: "user", sub: "u1", org: { id: "o1" }, iss: "me" })}.signature`;
  await signIn(page, "#/permissions/token");

  await page.getByLabel("A sample token").fill(token);
  await page.getByRole("button", { name: "Read claims" }).click();
  await expect(page.getByRole("table", { name: "Claims in the token" })).toContainText("org.id");
  await expect(page.getByLabel("A sample token")).toHaveValue("");
  await page.getByRole("button", { name: "Use role as the role" }).click();
  await page.getByRole("button", { name: "Use sub as the user id" }).click();
  await page.getByRole("button", { name: "Pass org.id on as an extra value" }).click();
  await expect(page.getByLabel("Role claim", { exact: true })).toHaveValue("role");
  await page.getByRole("button", { name: "Apply to the document" }).click();

  await expect(page).toHaveURL(/#\/permissions\/yaml$/);
  await expect(page.locator(".cm-content")).toContainText("X-Excalibase");
  await saveButton(page).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save and apply" }).click();
  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();
  const claims = (await served(request)).jwt.claims;
  expect(claims).toMatchObject({ role: "role", userId: "sub", extra: { id: "org.id" } });
});

test("version history: pick an old version, see what differs, restore it after the question", async ({ page, request }) => {
  await putDocument(request, EXAMPLE + "\n# second\n");
  await putDocument(request, EXAMPLE + "\n# third\n");
  await signIn(page, "#/permissions/history");

  await expect(page.locator("#history-list tbody tr").first()).toBeVisible();
  await page.locator("#history-list tbody tr").first().getByRole("button", { name: /View the version/ }).click();
  await expect(page.locator("#diff")).toContainText("# second");
  await page.getByRole("button", { name: "Restore this version" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/is added to the history, so you can undo this/)).toBeVisible();
  await dialog.getByRole("button", { name: "Restore" }).click();

  await expect(page.getByText(/Restored\. The engine now serves version/)).toBeVisible();
  expect((await metadata(request)).document).toContain("# second");
});

test("the schema page: columns with their keys, relationships in words, and what a role cannot read", async ({ page, request }) => {
  await putDocument(request, SHOP);
  await signIn(page, "#/schema/public.orders");

  await expect(page.getByRole("heading", { name: "orders", level: 1 })).toBeVisible();
  await expect(page.getByRole("row", { name: /^id bigint Primary key/ })).toBeVisible();
  await expect(page.getByRole("row", { name: /customer_id bigint → customers/ })).toBeVisible();
  await expect(page.getByRole("row", { name: /internal_note text Hidden from user/ })).toBeVisible();
  const relations = page.getByTestId("relationship");
  await expect(relations).toHaveCount(2);
  await expect(relations.first()).toContainText("Each row in orders points to one row in customers");
  await expect(relations.nth(1)).toContainText("Each row in orders can have many rows in order_items");
  await expect(page.getByText(/internal_note.*hidden from role/)).toBeVisible();

  await relations.first().getByRole("link", { name: "customers" }).click();
  await expect(page.getByRole("heading", { name: "customers", level: 1 })).toBeVisible();
  await expect(page.getByText(/cannot read/)).toBeVisible();
});

test("replicas and settings say where things stand, and settings can check a text without saving it", async ({ page, request }) => {
  await signIn(page, "#/replicas");
  await expect(page.getByRole("cell", { name: "This replica" })).toBeVisible();
  await expect(page.getByText("Ready", { exact: true })).toBeVisible();

  await page.evaluate(() => { window.location.hash = "#/settings"; });
  await expect(page.getByText("Permissions come from", { exact: true })).toBeVisible();
  const before = (await metadata(request)).sourceVersion;
  await page.getByLabel("Text to check").fill(EXAMPLE);
  await page.getByRole("button", { name: "Check", exact: true }).click();
  await expect(page.getByText(/This text is fine/)).toBeVisible();
  await page.getByLabel("Text to check").fill("tables: nope");
  await page.getByRole("button", { name: "Check", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "This text is not valid" })).toBeVisible();
  expect((await metadata(request)).sourceVersion).toBe(before);
});

test("dark mode: follows the device, can be flipped, and is kept for the tab", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await signIn(page, "#/schema");
  const background = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(await background()).toBe("rgb(17, 17, 19)");

  await page.getByRole("button", { name: "Switch to the light theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  expect(await background()).toBe("rgb(247, 247, 245)");
  await page.reload();
  expect(await background()).toBe("rgb(247, 247, 245)");
});

test("a phone: the menu folds away behind a button and no page scrolls sideways", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await signIn(page);
  const menu = page.getByRole("navigation", { name: "Console" });
  await expect(menu.getByRole("link", { name: "Schema" })).toBeHidden();

  await menu.getByRole("button", { name: "Menu" }).click();
  await expect(menu.getByRole("link", { name: "Schema" })).toBeVisible();
  await menu.getByRole("link", { name: "Schema" }).click();
  await expect(menu.getByRole("link", { name: "Schema" })).toBeHidden();

  for (const target of PAGES) {
    await page.evaluate((value) => { window.location.hash = value; }, `#/${target}`);
    await expect(page.getByRole("main").getByRole("heading").first()).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    const wide = overflow <= 0 ? [] : await page.evaluate(() => [...document.querySelectorAll("body *")]
      .filter((element) => element.getBoundingClientRect().right > document.documentElement.clientWidth)
      .map((element) => element.tagName + "." + element.className).slice(0, 8));
    expect(overflow, `${target}: ${wide.join(" | ")}`).toBeLessThanOrEqual(0);
  }
});

test("by keyboard alone: sign in with Enter, skip to the page, reach and use the grid, close a question with Escape", async ({ page }) => {
  await page.goto(NEXT + "/console");
  await page.getByLabel("Admin secret").focus();
  await page.keyboard.type(SECRET);
  await page.keyboard.press("Enter");
  await expect(page.getByRole("navigation", { name: "Console" })).toBeVisible();
  await page.evaluate(() => { window.location.hash = "#/permissions/%40anon"; });
  await expect(page.getByRole("heading", { name: "What can “anon” do?" })).toBeVisible();

  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to the page" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  await expect(page).toHaveURL(/#\/permissions\/%40anon$/);

  const cell = page.getByRole("button", { name: /anon can delete todos: None/ });
  await cell.focus();
  await page.keyboard.press("Space");
  await expect(page.getByRole("button", { name: /anon can delete todos: All rows/ })).toBeFocused();
  const outline = await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
  expect(outline).not.toBe("none");
});

test("a table that is not in the database, an unknown page and an empty address never leave a blank page", async ({ page }) => {
  await signIn(page, "#/schema/public.ghost");
  await expect(page.getByText("That table is not here")).toBeVisible();
  await page.evaluate(() => { window.location.hash = "#/nowhere"; });
  await expect(page.getByRole("heading", { name: "Data" })).toBeVisible();
});

const SHOTS = process.env.SCREENSHOT_DIR;
test("screenshots of each page, light, dark and on a phone", async ({ page, request }) => {
  test.skip(!SHOTS, "set SCREENSHOT_DIR to write the pictures");
  fs.mkdirSync(SHOTS, { recursive: true });
  await putDocument(request, SHOP);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page);
  const shoot = async (name) => page.screenshot({ path: path.join(SHOTS, name + ".png"), fullPage: false });
  const open = async (hash, heading) => {
    await page.evaluate((value) => { window.location.hash = value; }, hash);
    await expect(page.getByRole("main").getByRole("heading", { name: heading }).first()).toBeVisible();
    await page.waitForTimeout(500);
  };
  await open("#/schema/public.orders", "orders"); await shoot("schema-light");
  await open("#/permissions/%40user", /What can/); await shoot("permissions-light");
  await open("#/permissions/yaml", "Permissions as YAML"); await shoot("yaml-light");
  await open("#/permissions/token", "Sample token"); await shoot("token-light");
  await open("#/permissions/history", "Version history"); await shoot("history-light");
  await open("#/replicas", "Replicas"); await shoot("replicas-light");
  await open("#/settings", "Settings"); await shoot("settings-light");
  await open("#/explorer", "API explorer"); await shoot("explorer-light");
  await page.emulateMedia({ colorScheme: "dark" });
  await open("#/schema/public.orders", "orders"); await shoot("schema-dark");
  await open("#/permissions/%40user", /What can/); await shoot("permissions-dark");
  await open("#/permissions/%40anon", /What can/);
  await page.getByRole("button", { name: /anon can delete todos: None/ }).click();
  await saveButton(page).click();
  await expect(page.getByRole("dialog")).toBeVisible(); await shoot("save-question-dark");
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 390, height: 844 });
  await open("#/permissions/%40user", /What can/); await shoot("permissions-phone-dark");
  await page.emulateMedia({ colorScheme: "light" });
  await page.evaluate(() => window.sessionStorage.setItem("excalibase.console.theme", "light"));
  await page.reload();
  await open("#/schema/public.orders", "orders"); await shoot("schema-phone-light");
  await page.getByRole("button", { name: "Menu" }).click(); await shoot("menu-phone-light");
});
