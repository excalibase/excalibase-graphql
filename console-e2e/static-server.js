// Serves .work/served over plain http on localhost: the document the read-only engine fetches (the url source).
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const file = path.join(__dirname, ".work", "served", "permissions.yaml");
http.createServer((request, response) => {
  response.setHeader("Content-Type", "text/yaml");
  response.end(fs.readFileSync(file));
}).listen(Number(process.env.STATIC_PORT || 9099), "127.0.0.1");
