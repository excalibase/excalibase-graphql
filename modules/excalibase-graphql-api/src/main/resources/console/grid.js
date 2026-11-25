import { h } from "/console/dom.js";
import { OPERATIONS, allows, isCustom, rolesOf, tableKeysOf, validRole } from "/console/docmodel.js";

const SHORT = { select: "S", insert: "I", update: "U", delete: "D" };
const DEFAULT_ROLES = ["anon", "user"];

/** The roles the grid has a column for: those the rules name, the ones added here, and anon and user when empty. */
export function columnRoles(tree, added) {
  const named = [...new Set([...rolesOf(tree), ...added])].sort();
  return named.length === 0 ? DEFAULT_ROLES : named;
}

function cell(tree, table, role, onToggle) {
  const boxes = OPERATIONS.map((operation) => {
    const custom = isCustom(tree, table, role, operation);
    return h("label", { class: "op", title: custom ? operation + ": a custom rule, edit it as text" : operation },
      h("input", { type: "checkbox", checked: allows(tree, table, role, operation),
        "aria-label": operation + " " + table + " " + role,
        onchange: (event) => onToggle(table, role, operation, event.target.checked) }),
      SHORT[operation] + (custom ? "*" : ""));
  });
  return h("td", {}, boxes);
}

function addRole(onAddRole, message) {
  const input = h("input", { type: "text", id: "new-role", autocomplete: "off", spellcheck: "false",
    placeholder: "new role, e.g. editor" });
  return h("div", { class: "actions" },
    h("label", { for: "new-role" }, "Add a role"), input,
    h("button", { type: "button", class: "secondary", onclick: () => onAddRole(input.value.trim()) }, "Add"),
    message ? h("span", { class: "error", role: "alert" }, message) : null);
}

export function roleProblem(name, known) {
  if (!validRole(name)) {
    return "A role is lowercase letters, digits and underscores, starts with a letter, and is not service.";
  }
  return known.includes(name) ? "That role is already a column." : null;
}

export function gridView(tree, schemaTables, added, handlers, message) {
  const roles = columnRoles(tree, added);
  const tables = tableKeysOf(schemaTables, tree);
  const head = h("tr", {}, h("th", {}, "Table"), roles.map((role) => h("th", {}, role)));
  const rows = tables.map((table) => h("tr", {}, h("th", { role: "rowheader" }, table),
    roles.map((role) => cell(tree, table, role, handlers.onToggle))));
  return h("section", {},
    h("p", { class: "hint" },
      "A ticked box gives the role that operation on every row and every column. A star marks a rule written by hand, "
      + "which stays as it is. The service role always has full access and has no column."),
    h("div", { class: "scroll" }, h("table", { class: "grid" }, h("thead", {}, head), h("tbody", {}, rows))),
    tables.length === 0 ? h("p", {}, "No tables are exposed.") : null,
    addRole(handlers.onAddRole, message),
    h("p", { class: "hint" }, "Saving from the grid writes the document as JSON, so comments in a YAML file are not kept."));
}
