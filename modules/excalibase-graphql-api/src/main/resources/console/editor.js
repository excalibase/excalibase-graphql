import { h } from "/console/dom.js";

/** What a save came to, from the status the admin API answered with. Server text is shown as text, never as markup. */
export function classify(failure) {
  const body = failure.body || {};
  switch (failure.status) {
    case 409:
      return { kind: "conflict", currentVersion: body.currentVersion };
    case 422:
      return { kind: "invalid", errors: Array.isArray(body.errors) ? body.errors : [] };
    case 405:
      return { kind: "readonly" };
    case 428:
      return { kind: "missing" };
    case 500:
      return { kind: "refused" };
    case 502:
      return { kind: "unreachable" };
    default:
      return { kind: "failed", code: failure.code };
  }
}

const TEXTS = {
  saved: (outcome) => "Saved. The engine now serves version " + outcome.version + ".",
  readonly: () => "This source is read-only. Nothing was saved; use Export to take a copy.",
  missing: () => "The console did not send the version it started from, so nothing was saved. Reload the page.",
  refused: () => "The document was written, but the engine could not load it. The last good document is still served.",
  unreachable: () => "The source could not be written, so nothing was saved. The last good document is still served.",
  failed: (outcome) => "The save failed (" + outcome.code + ")."
};

function errorList(errors) {
  return h("ul", {}, errors.map((message) => h("li", {}, message)));
}

function conflictView(outcome, theirs, handlers) {
  return h("div", { class: "error", role: "alert" },
    h("p", {}, "Someone else saved first (their version is " + (theirs ? theirs.sourceVersion : outcome.currentVersion)
      + "). Your edits were not saved."),
    theirs ? h("pre", { class: "current-text", id: "theirs" }, theirs.text == null ? "Nothing is served." : theirs.text) : null,
    h("div", { class: "actions" },
      h("button", { type: "button", onclick: handlers.onTakeTheirs }, "Use their version"),
      h("button", { type: "button", class: "danger", onclick: handlers.onOverwrite },
        "Keep mine and overwrite theirs")));
}

/** The one place a save outcome is explained. A conflict never overwrites on its own: the person picks. */
export function outcomeView(outcome, theirs, handlers) {
  if (!outcome) {
    return null;
  }
  if (outcome.kind === "conflict") {
    return conflictView(outcome, theirs, handlers);
  }
  if (outcome.kind === "invalid") {
    return h("div", { class: "error", role: "alert" }, h("p", {}, "Not saved: the document is not valid."),
      errorList(outcome.errors));
  }
  const text = TEXTS[outcome.kind](outcome);
  return h("p", { class: outcome.kind === "saved" ? "ok" : "error", role: outcome.kind === "saved" ? "status" : "alert" }, text);
}

/** Save is off on a source that cannot be written; Export is then the way to take the document away. */
export function saveBar(state, handlers) {
  const writable = state.meta.writable === true;
  const dirty = state.draft !== state.meta.text;
  return h("section", { class: "savebar" },
    h("button", { type: "button", id: "save", disabled: !writable || !dirty, onclick: handlers.onSave }, "Save"),
    h("button", { type: "button", id: "export", class: writable ? "secondary" : "", onclick: handlers.onExport }, "Export"),
    dirty ? h("button", { type: "button", class: "link", onclick: handlers.onDiscard }, "Discard edits") : null,
    h("span", { class: "hint", role: "status" }, hint(writable, dirty)),
    outcomeView(state.outcome, state.theirs, handlers));
}

function hint(writable, dirty) {
  if (!writable) {
    return "Read-only source: edits cannot be saved here. Export saves a copy as a file.";
  }
  return dirty ? "Unsaved edits." : "No unsaved edits.";
}

export function documentView(state, onEdit) {
  const input = h("textarea", { id: "draft", rows: 24, spellcheck: "false", "aria-label": "Permissions document",
    oninput: (event) => onEdit(event.target.value) });
  input.value = state.draft == null ? "" : state.draft;
  return h("section", {},
    state.meta.text == null ? h("p", { class: "error" }, "No valid document is served yet.") : null,
    input);
}
