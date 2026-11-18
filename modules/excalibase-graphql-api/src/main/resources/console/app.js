import { ApiError, getMetadata, getReplicas, getSchema, signIn, signOut, signedIn, validateDocument } from "/console/api.js";
import { h, mount } from "/console/dom.js";
import { describe, documentView, loginView, replicasView, tablesView, validateView } from "/console/views.js";

const root = document.getElementById("root");
const TABS = [
  { id: "document", label: "Document", load: async () => documentView(await getMetadata()) },
  { id: "tables", label: "Tables", load: async () => tablesView(await getSchema()) },
  { id: "replicas", label: "Replicas", load: async () => replicasView(await getReplicas()) },
  { id: "validate", label: "Validate", load: async () => validateView(runValidation, null) }
];

let active = "document";

function showLogin(error) {
  mount(root, loginView(async (secret) => {
    try {
      await signIn(secret);
      await showTab(active);
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
    h("nav", { role: "tablist" }, tabs), h("main", {}, content));
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

async function runValidation(text) {
  try {
    shell(validateView(runValidation, await validateDocument(text), text));
  } catch (failure) {
    handleFailure(failure);
  }
}

if (signedIn()) {
  showTab(active);
} else {
  showLogin(null);
}
