// Gives the test engines a fresh, writable permissions file: a copy of the standalone example, so the test and the
// documented example cannot drift apart. The engine under test saves into this copy, never into the example.
const fs = require("node:fs");
const path = require("node:path");

const work = path.join(__dirname, ".work");
fs.rmSync(work, { recursive: true, force: true });
fs.mkdirSync(path.join(work, "served"), { recursive: true });
const example = fs.readFileSync(path.join(__dirname, "..", "deploy", "standalone", "metadata", "permissions.yaml"));
fs.writeFileSync(path.join(work, "permissions.yaml"), example);
fs.writeFileSync(path.join(work, "permissions-next.yaml"), example);
fs.writeFileSync(path.join(work, "served", "permissions.yaml"), example);
