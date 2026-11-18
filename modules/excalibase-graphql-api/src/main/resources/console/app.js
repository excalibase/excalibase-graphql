import { ApiError, getMetadata, getReplicas, getSchema, parseText, putMetadata, signIn, signOut, signedIn,
  validateText } from "/console/api.js";
import { exportName, serialize, setOperation, withJwt } from "/console/docmodel.js";
import { h, mount, rootElement, saveAs } from "/console/dom.js";
import { classify, documentView, saveBar } from "/console/editor.js";
import { gridView, roleProblem, columnRoles } from "/console/grid.js";
import { jwtView } from "/console/jwt.js";
import { describe, loginView, metaFields, replicasView, tablesView, validateView } from "/console/views.js";

const root = rootElement();
const TABS = [
  { id: "document", label: "Document", load: async () => documentView(state, edit) },
  { id: "grid", label: "Grid", load: gridTab },
  { id: "token", label: "Sample token", load: tokenTab },
  { id: "tables", label: "Tables", load: async () => tablesView(await getSchema()) },
  { id: "replicas", label: "Replicas", load: async () => replicasView(await getReplicas()) },
  { id: "validate", label: "Validate", load: async () => validateView(runValidation, null) }
];

// What the person is working on: the document as served (meta), their edits (draft), the tree the grid edits, and
// what the last save came to. A tree is read from the draft by the engine, so a YAML document is understood there.
const state = { meta: null, draft: "", tree: null, roles: [], outcome: null, theirs: null, grid: null };
let active = "document";

function showLogin(error) {
  mount(root, loginView(async (secret) => {
    try {
      await signIn(secret);
      await begin("document");
    } catch (failure) {
      showLogin(failure instanceof ApiError ? describe(failure) : "The engine could not be reached.");
    }
  }, error));
}

function shell(content) {
  const tabs = TABS.map((tab) => h("button", { type: "button", role: "tab", "aria-selected": tab.id === active,
    class: tab.id === active ? "tab active" : "tab", onclick: () => showTab(tab.id) }, tab.label));
  mount(root, h("header", {}, h("strong", {}, "Excalibase console"),
    h("button", { type: "button", class: "link", onclick: () => { signOut(); showLogin(null); } }, "Sign out")),
    h("nav", { role: "tablist" }, tabs),
    h("main", {}, state.meta ? metaFields(state.meta) : null, h("div", { id: "bar" }, state.meta ? saveBar(state, actions) : null), content));
}

async function begin(id) {
  try {
    await loadMetadata();
    await showTab(id);
  } catch (failure) {
    handleFailure(failure);
  }
}

async function loadMetadata() {
  state.meta = await getMetadata();
  state.draft = state.meta.text == null ? "" : state.meta.text;
  state.tree = null;
}

async function showTab(id) {
  active = id;
  try {
    shell(await TABS.find((tab) => tab.id === id).load());
  } catch (failure) {
    handleFailure(failure);
  }
}

function handleFailure(failure) {
  if (failure instanceof ApiError && failure.status === 401) {
    showLogin("Your session ended. Sign in again.");
    return;
  }
  shell(h("p", { class: "error", role: "alert" },
    failure instanceof ApiError ? describe(failure) : "The engine could not be reached."));
}

function edit(text) {
  state.draft = text;
  state.tree = null;
  state.outcome = null;
  refreshBar();
}

/** Typing must not rebuild the page (the cursor would leave the text), so only the bar above it is redrawn. */
function refreshBar() {
  const slot = root.querySelector("#bar");
  if (slot) {
    mount(slot, saveBar(state, actions));
  }
}

async function readTree() {
  if (state.tree) {
    return state.tree;
  }
  const answer = await parseText(state.draft);
  state.tree = answer.valid ? answer.tree : null;
  state.treeErrors = answer.valid ? [] : answer.errors;
  return state.tree;
}

function invalidDraft() {
  return h("div", { class: "error", role: "alert" }, h("p", {}, "The grid needs a valid text. Fix it on the Document tab:"),
    h("ul", {}, (state.treeErrors || []).map((message) => h("li", {}, message))));
}

async function gridTab(message = null) {
  const tree = await readTree();
  if (!tree) {
    return invalidDraft();
  }
  const schema = await getSchema().catch(() => ({ tables: [] }));
  return gridView(tree, schema.tables, state.roles, { onToggle: toggle, onAddRole: addRole }, message);
}

function toggle(table, role, operation, on) {
  state.tree = setOperation(state.tree, table, role, operation, on);
  state.draft = serialize(state.tree);
  state.outcome = null;
  refreshBar();
}

async function addRole(name) {
  const problem = roleProblem(name, columnRoles(state.tree, state.roles));
  if (!problem) {
    state.roles = [...state.roles, name];
  }
  shell(await gridTab(problem));
}

async function tokenTab() {
  const tree = await readTree();
  if (!tree) {
    return invalidDraft();
  }
  return jwtView(tree.jwt, (section) => {
    state.tree = withJwt(state.tree, Object.keys(section).length > 0 ? section : null);
    state.draft = serialize(state.tree);
    state.outcome = null;
    showTab("document");
  });
}

async function save() {
  try {
    const saved = await putMetadata(state.draft, state.meta.sourceVersion);
    await loadMetadata();
    state.outcome = { kind: "saved", version: saved.version };
    state.theirs = null;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) {
      handleFailure(failure);
      return;
    }
    state.outcome = failure instanceof ApiError ? classify(failure) : { kind: "failed", code: "unreachable" };
    state.theirs = state.outcome.kind === "conflict" ? await fetchTheirs() : null;
  }
  state.tree = null;
  await showTab(active);
}

async function fetchTheirs() {
  try {
    return await getMetadata();
  } catch {
    return null;
  }
}

/** Their version becomes the base, and the person's text stays: the next save replaces theirs, by choice. */
async function overwrite() {
  const theirs = state.theirs;
  state.meta = { ...state.meta, sourceVersion: state.outcome.currentVersion, text: theirs ? theirs.text : state.meta.text };
  state.outcome = null;
  state.theirs = null;
  await showTab(active);
}

async function takeTheirs() {
  await loadMetadata();
  state.outcome = null;
  state.theirs = null;
  await showTab(active);
}

function exportDocument() {
  const { filename, type } = exportName(state.draft);
  saveAs(filename, state.draft, type);
}

function discard() {
  state.draft = state.meta.text == null ? "" : state.meta.text;
  state.tree = null;
  state.outcome = null;
  showTab(active);
}

const actions = { onSave: save, onExport: exportDocument, onDiscard: discard, onTakeTheirs: takeTheirs, onOverwrite: overwrite };

async function runValidation(text) {
  try {
    shell(validateView(runValidation, await validateText(text), text));
  } catch (failure) {
    handleFailure(failure);
  }
}

if (signedIn()) {
  begin(active);
} else {
  showLogin(null);
}
