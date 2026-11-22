const path = require("node:path");
const { defineConfig } = require("@playwright/test");
const ports = require("./ports");

const JAR = process.env.ENGINE_JAR
  || path.join(__dirname, "..", "modules", "excalibase-graphql-api", "target", "excalibase-graphql-api-0.0.1-SNAPSHOT.jar");
const DATABASE = process.env.E2E_DATABASE_URL || "jdbc:postgresql://localhost:55432/app";
const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const WORK = path.join(__dirname, ".work");

// The same engine three times: one with a writable file source, one with a read-only url source, and one serving the
// new console (EXCALIBASE_CONSOLE_UI=next) from its own copy of the document, each on its own ports.
function engine({ name, port, adminPort, managementPort, source, ui = "legacy" }) {
  return {
    name,
    command: `java -jar ${JAR}`,
    url: `http://localhost:${adminPort}/console`,
    timeout: 120_000,
    reuseExistingServer: false,
    stdout: "ignore",
    env: {
      SERVER_PORT: String(port),
      MANAGEMENT_SERVER_PORT: String(managementPort),
      SPRING_DATASOURCE_URL: DATABASE,
      SPRING_DATASOURCE_USERNAME: "app",
      SPRING_DATASOURCE_PASSWORD: "change-me",
      APP_DATABASE_TYPE: "postgres",
      APP_PROJECT_ID: "my-app",
      EXCALIBASE_CONSOLE_ENABLED: "true",
      EXCALIBASE_ADMIN_SECRET: SECRET,
      EXCALIBASE_ADMIN_PORT: String(adminPort),
      EXCALIBASE_CONSOLE_UI: ui,
      JWT_SECRET: "e2e-token-secret-0123456789-abcdefghijklmnop",
      E2E_JWT_SECRET: "e2e-token-secret-0123456789-abcdefghijklmnop",
      ...source
    }
  };
}

module.exports = defineConfig({
  testDir: "./tests",
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { baseURL: ports.WRITABLE, trace: "retain-on-failure", acceptDownloads: true },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: [
    engine({ name: "writable", port: ports.writable.port, adminPort: ports.writable.admin,
      managementPort: ports.writable.management,
      source: { METADATA_SOURCE: "file", METADATA_FILE_PATH: path.join(WORK, "permissions.yaml"),
        METADATA_FILE_POLL_INTERVAL_MS: "200" } }),
    { name: "static", command: "node static-server.js", url: `http://127.0.0.1:${ports.staticPort}`,
      reuseExistingServer: false },
    engine({ name: "read-only", port: ports.readOnly.port, adminPort: ports.readOnly.admin,
      managementPort: ports.readOnly.management,
      source: { METADATA_SOURCE: "url", METADATA_URL_LOCATION: `http://localhost:${ports.staticPort}/permissions.yaml` } }),
    engine({ name: "next", port: ports.next.port, adminPort: ports.next.admin, managementPort: ports.next.management,
      ui: "next",
      source: { METADATA_SOURCE: "file", METADATA_FILE_PATH: path.join(WORK, "permissions-next.yaml"),
        METADATA_FILE_POLL_INTERVAL_MS: "200" } })
  ]
});
