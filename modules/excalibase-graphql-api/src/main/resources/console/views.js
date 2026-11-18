import { h } from "/console/dom.js";

const MESSAGES = {
  unauthorized: "That secret was not accepted.",
  too_many_failed_attempts: "Too many failed attempts. Wait a minute and try again.",
  no_metadata_source: "This engine has no permissions text.",
  schema_unavailable: "The schema is not loaded yet.",
  invalid_document: "The document is not valid."
};

export function describe(failure) {
  return MESSAGES[failure.code] || "The request failed (" + failure.code + ").";
}

function field(label, value) {
  return h("div", { class: "field" }, h("dt", {}, label), h("dd", {}, value == null ? "none" : value));
}

export function loginView(onSubmit, error) {
  const input = h("input", { type: "password", id: "secret", autocomplete: "off", spellcheck: "false" });
  const form = h("form", { class: "card", onsubmit: (event) => { event.preventDefault(); onSubmit(input.value); } },
    h("h1", {}, "Excalibase console"),
    h("label", { for: "secret" }, "Admin secret"),
    input,
    h("button", { type: "submit" }, "Sign in"),
    error ? h("p", { class: "error", role: "alert" }, error) : null);
  return form;
}

export function metaFields(meta) {
  return h("dl", { class: "fields" },
    field("Source", meta.source),
    field("Version", meta.version),
    field("Source version", meta.sourceVersion),
    field("Mode", meta.writable ? "writable" : "read-only"),
    field("Last refusal", meta.lastRefusal ? meta.lastRefusal + " (" + meta.lastRefusalAt + ")" : null));
}

function columnList(table) {
  return table.columns.map((column) => column.name + (column.primaryKey ? " (key)" : "") + ": " + column.type).join(", ");
}

export function tablesView(schema) {
  const rows = schema.tables.map((table) => h("tr", {},
    h("td", {}, table.schema + "." + table.name),
    h("td", {}, table.kind),
    h("td", {}, columnList(table))));
  return h("section", {},
    h("table", {}, h("thead", {}, h("tr", {}, h("th", {}, "Table"), h("th", {}, "Kind"), h("th", {}, "Columns"))),
      h("tbody", {}, rows)),
    schema.tables.length === 0 ? h("p", {}, "No tables are exposed.") : null);
}

export function replicasView(body) {
  const rows = body.replicas.map((replica) => h("tr", {},
    h("td", {}, replica.self ? "this replica" : "other"),
    h("td", {}, replica.version),
    h("td", {}, replica.ready ? "ready" : "not ready"),
    h("td", {}, replica.source)));
  return h("section", {},
    h("table", {}, h("thead", {}, h("tr", {}, h("th", {}, "Replica"), h("th", {}, "Version"),
      h("th", {}, "State"), h("th", {}, "Source"))), h("tbody", {}, rows)));
}

export function validateView(onValidate, outcome, text = "") {
  const input = h("textarea", { id: "candidate", rows: 14, spellcheck: "false",
    placeholder: "Paste permissions YAML" });
  input.value = text;
  return h("section", {},
    h("label", { for: "candidate" }, "Text to check"),
    input,
    h("button", { type: "button", onclick: () => onValidate(input.value) }, "Validate"),
    outcomeView(outcome));
}

function outcomeView(outcome) {
  if (!outcome) {
    return null;
  }
  if (outcome.valid) {
    return h("p", { class: "ok", role: "status" },
      "Valid: " + outcome.tables + " table rules, " + outcome.functions + " functions.");
  }
  return h("div", { class: "error", role: "alert" }, h("p", {}, "Not valid:"),
    h("ul", {}, outcome.errors.map((message) => h("li", {}, message))));
}
