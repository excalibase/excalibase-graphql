const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { test, expect } = require("@playwright/test");
const { NEXT } = require("../ports");

// Settings, then Auth, driven the way a person drives it, against a real engine that has the engine's own token
// settings (APP_AUTH_*) and the Auth page on. Tokens are signed here with the engine's secret.
const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const TOKEN_SECRET = "e2e-token-secret-0123456789-abcdefghijklmnop";
const AUTH_PAGE = "#/settings/auth";
const AUDIENCE = "my-app-api";
const ADMIN = NEXT + "/_admin/v1";
const AUTH = { "X-Excalibase-Admin-Secret": SECRET };
const EXAMPLE = fs.readFileSync(path.join(__dirname, "..", "..", "deploy", "standalone", "metadata", "permissions.yaml"), "utf8");
const SAME_JWT = EXAMPLE.replace("tables:", `jwt:
  secret: env:E2E_JWT_SECRET
  algorithms: [HS256]
  audience: my-app-api
  claims:
    role: role
    userId: sub
tables:`);

const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");

function sign(claims, secret = TOKEN_SECRET) {
  const head = encode({ alg: "HS256", typ: "JWT" });
  const body = encode({ sub: "u-1", aud: AUDIENCE, role: "user", exp: Math.floor(Date.now() / 1000) + 600, ...claims });
  const signature = crypto.createHmac("sha256", secret).update(`${head}.${body}`).digest("base64url");
  return `${head}.${body}.${signature}`;
}

async function metadata(request) {
  return (await request.get(ADMIN + "/metadata", { headers: AUTH })).json();
}

async function putDocument(request, text) {
  const current = await metadata(request);
  const saved = await request.put(ADMIN + "/metadata", { headers: { ...AUTH, "Content-Type": "text/plain",
    "If-Match": `"${current.sourceVersion}"` }, data: text });
  expect(saved.status(), await saved.text()).toBe(200);
}

async function signIn(page, hash) {
  await page.goto(NEXT + "/console");
  await page.getByLabel("Admin secret").fill(SECRET);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Console" })).toBeVisible();
  await page.evaluate((value) => { window.location.hash = value; }, hash);
  await expect(page.getByRole("heading", { name: "Auth", level: 1 })).toBeVisible();
}

test.beforeEach(async ({ request }) => {
  await putDocument(request, EXAMPLE);
});

test("the page reads the engine's settings and never shows the secret", async ({ page }) => {
  await signIn(page, AUTH_PAGE);

  const facts = page.getByRole("region", { name: "How tokens are checked now" });
  await expect(facts).toContainText("The engine's own settings (APP_AUTH_*)");
  await expect(facts).toContainText("HS256");
  await expect(facts).toContainText(AUDIENCE);
  await expect(facts.locator("dd", { hasText: /^Set$/ })).toBeVisible();
  await expect(page.locator("body")).not.toContainText(TOKEN_SECRET);
});

test("a token is tested by the engine: good, expired and wrongly signed, and it is kept nowhere", async ({ page }) => {
  const checks = [];
  page.on("request", (outgoing) => { if (outgoing.method() === "POST") { checks.push(outgoing); } });
  await signIn(page, AUTH_PAGE);
  const box = page.getByLabel("Token to test");
  const test_ = page.getByRole("button", { name: "Test this token" });

  const good = sign({});
  await box.fill(good);
  await test_.click();
  await expect(page.getByText("The engine accepts this token.")).toBeVisible();
  await expect(page.getByRole("table", { name: "Values a rule can use" })).toContainText("x-excalibase-user-id");
  await expect(box).toHaveValue("");

  await box.fill(sign({ exp: Math.floor(Date.now() / 1000) - 3600 }));
  await test_.click();
  await expect(page.getByRole("alert").filter({ hasText: "The token has expired" })).toBeVisible();

  await box.fill(sign({}, "another-secret-another-secret-another-secret"));
  await test_.click();
  await expect(page.getByRole("alert").filter({ hasText: "signature does not match" })).toBeVisible();

  const stored = await page.evaluate(() => JSON.stringify({ ...window.sessionStorage, ...window.localStorage }));
  expect(stored).not.toContain(good.split(".")[2]);
  expect(stored).not.toContain("eyJ");
  await expect(page.locator("body")).not.toContainText(good.split(".")[2]);
  expect(checks.map((call) => new URL(call.url()).pathname)).toEqual(Array(3).fill("/_admin/v1/auth/check"));
  expect(checks[0].postData()).toBe(good);
});

test("the settings are written as engine variables from a sample token read in the browser", async ({ page }) => {
  const sent = [];
  page.on("request", (outgoing) => sent.push(outgoing.postData() || ""));
  await signIn(page, AUTH_PAGE);
  const sample = `${encode({ alg: "HS256" })}.${encode({ role: "user", sub: "u1", org: { id: "o1" } })}.sample-signature-1`;

  await page.getByLabel("A sample token").fill(sample);
  await page.getByRole("button", { name: "Read claims" }).click();
  await expect(page.getByRole("table", { name: "Claims in the token" })).toContainText("org.id");
  await expect(page.getByLabel("A sample token")).toHaveValue("");
  await page.getByRole("button", { name: "Use sub as the user id" }).click();
  await page.getByRole("button", { name: "Pass org.id on as an extra value" }).click();

  const env = page.getByLabel("Environment variables");
  await expect(env).toContainText("APP_AUTH_CLAIMS_USER_ID=sub");
  await expect(env).toContainText("APP_AUTH_CLAIMS_EXTRA=id=org.id");
  await expect(env).not.toContainText(TOKEN_SECRET);
  await expect(page.getByLabel("Kubernetes env block")).toContainText("secretKeyRef");
  expect(sent.join("\n")).not.toContain("sample-signature-1");
});

test("a document jwt: section that says the same is removed in one save, and the page reads the settings again", async ({ page, request }) => {
  await putDocument(request, SAME_JWT);
  await signIn(page, AUTH_PAGE);
  await expect(page.getByTestId("token-notes")).toContainText("says the same");

  const remove = page.getByRole("button", { name: "Remove jwt: from the document" });
  await expect(remove).toBeEnabled();
  await remove.click();
  await page.getByRole("dialog").getByRole("button", { name: "Save and apply" }).click();

  await expect(page.getByText(/Saved\. The engine now uses version/)).toBeVisible();
  expect((await metadata(request)).document).not.toContain("jwt");
  await expect(remove).toHaveCount(0);
  await expect(page.getByTestId("token-notes")).toHaveCount(0);
});

test("a document jwt: section that differs is refused by the engine, never served", async ({ request }) => {
  const different = SAME_JWT.replace(AUDIENCE, "another-api");
  const current = await metadata(request);

  const saved = await request.put(ADMIN + "/metadata", { headers: { ...AUTH, "Content-Type": "text/plain",
    "If-Match": `"${current.sourceVersion}"` }, data: different });

  expect(saved.status()).toBe(422);
  expect((await metadata(request)).document).toBe(EXAMPLE);
});
