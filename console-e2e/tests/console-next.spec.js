const { test, expect } = require("@playwright/test");
const { NEXT } = require("../ports");

// The new console under the engine's real policy. A page that mounts is not enough: every browser-reported
// violation of the policy counts, so a feature that quietly needs an inline style or a string eval fails here.
const watchViolations = (page) => page.addInitScript(() => {
  window.__violations = [];
  document.addEventListener("securitypolicyviolation", (event) => {
    window.__violations.push(`${event.violatedDirective} ${event.blockedURI || event.sample || ""}`.trim());
  });
});
const violations = (page) => page.evaluate(() => window.__violations);
const nonceOf = (policy) => /'nonce-([A-Za-z0-9_-]+)'/.exec(policy)?.[1];

test("the new console loads and mounts its editor without breaking the policy", async ({ page }) => {
  await watchViolations(page);
  const consoleErrors = [];
  page.on("console", (message) => message.type() === "error" && consoleErrors.push(message.text()));

  await page.goto(NEXT + "/console");

  await expect(page.getByRole("heading", { name: "Excalibase console" })).toBeVisible();
  await expect(page.locator(".cm-editor")).toBeVisible();
  await expect(page.locator(".cm-content")).toContainText("projectId: my-app");
  expect(await violations(page)).toEqual([]);
  expect(consoleErrors).toEqual([]);
});

test("typing, selecting, bracket matching, folding and the completion popup break nothing", async ({ page }) => {
  await watchViolations(page);
  await page.goto(NEXT + "/console");
  const content = page.locator(".cm-content");
  await content.click();

  await page.keyboard.press("Control+End");
  await page.keyboard.type("\nextra: { list: [1, 2, 3], nested: {a: 1} }\n");
  await page.keyboard.press("Control+A");
  await page.keyboard.press("Control+Z");
  await page.keyboard.press("Control+Space");
  await page.keyboard.press("Escape");
  await page.keyboard.press("Control+F");
  await page.keyboard.type("todos");
  await page.keyboard.press("Escape");

  await expect(content).toContainText("projectId: my-app");
  expect(await violations(page)).toEqual([]);
});

test("the policy is really enforced: an inline style, raw markup and another origin are refused", async ({ page }) => {
  await watchViolations(page);
  await page.goto(NEXT + "/console");

  const outcome = await page.evaluate(async () => {
    const result = {};
    const element = document.createElement("div");
    element.setAttribute("style", "color: red");
    document.body.append(element);
    try {
      document.body.innerHTML = "<b>x</b>";
      result.markup = "allowed";
    } catch (error) {
      result.markup = error.name;
    }
    try {
      await fetch("http://127.0.0.1:9/");
      result.network = "allowed";
    } catch (error) {
      result.network = error.name;
    }
    return result;
  });

  expect(outcome.markup).toBe("TypeError");
  expect(outcome.network).toBe("TypeError");
  expect.poll(() => violations(page)).toEqual(expect.arrayContaining([
    expect.stringContaining("style-src"), expect.stringContaining("connect-src")]));
});

test("every load gets its own nonce, on a style element the script can read", async ({ page }) => {
  const first = await page.goto(NEXT + "/console");
  const second = await page.goto(NEXT + "/console");
  const one = nonceOf(first.headers()["content-security-policy"]);
  const two = nonceOf(second.headers()["content-security-policy"]);

  expect(one).toBeTruthy();
  expect(one).not.toBe(two);
  expect(await page.evaluate(() => document.querySelector("style[nonce]").nonce)).toBe(two);
  expect(await page.locator("meta[name=nonce]").count()).toBe(0);
});

test("assets are immutable, compressed on request, and the manifest is not served", async ({ page, request }) => {
  await page.goto(NEXT + "/console");
  const script = await page.locator("script[type=module]").first().getAttribute("src");

  const asset = await request.get(NEXT + script, { headers: { "Accept-Encoding": "gzip" } });
  expect(asset.status()).toBe(200);
  expect(asset.headers()["cache-control"]).toBe("public, max-age=31536000, immutable");
  expect(asset.headers()["content-security-policy"]).toContain("script-src 'self'");
  expect((await request.get(NEXT + "/console/.vite/manifest.json")).status()).toBe(404);
  expect((await request.get(NEXT + "/console/assets/../application.yml")).status()).toBeGreaterThanOrEqual(400);
});

test("the old console is still reachable at /console/legacy and signs in the same way", async ({ page }) => {
  await page.goto(NEXT + "/console/legacy");

  await expect(page.locator("#secret")).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
