const path = require("node:path");
const { defineConfig } = require("@playwright/test");

const JAR = process.env.ENGINE_JAR
  || path.join(__dirname, "..", "modules", "excalibase-graphql-api", "target", "excalibase-graphql-api-0.0.1-SNAPSHOT.jar");
const DATABASE = process.env.E2E_DATABASE_URL || "jdbc:postgresql://localhost:55432/app";
const SECRET = "e2e-admin-secret-0123456789-abcdefghijklmnop";
const WORK = path.join(__dirname, ".work");

// The same engine twice: one with a writable file source, one with a read-only url source, on their own ports.
function engine({ name, port, adminPort, managementPort, source }) {
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
  use: { baseURL: "http://localhost:10001", trace: "retain-on-failure", acceptDownloads: true },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: [
    engine({ name: "writable", port: 10000, adminPort: 10001, managementPort: 18081,
      source: { METADATA_SOURCE: "file", METADATA_FILE_PATH: path.join(WORK, "permissions.yaml"),
        METADATA_FILE_POLL_INTERVAL_MS: "200" } }),
    { name: "static", command: "node static-server.js", url: "http://127.0.0.1:9099", reuseExistingServer: false },
    engine({ name: "read-only", port: 10010, adminPort: 10011, managementPort: 18082,
      source: { METADATA_SOURCE: "url", METADATA_URL_LOCATION: "http://localhost:9099/permissions.yaml" } })
  ]
});
