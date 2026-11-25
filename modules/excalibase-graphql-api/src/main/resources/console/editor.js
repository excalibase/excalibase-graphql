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

/** A save that went ahead without the History taking the old text: said out loud, never silent. */
export function historyWarning() {
  return h("p", { class: "warn", role: "alert" }, "Saved, but this version could not be added to History.");
}

/** Notes about how tokens are checked: they say nothing about the database and get their own notice. */
const TOKEN_NOTES = new Set(["jwt_deprecated", "jwt_identical_remove_it", "no_token_verifier"]);
/** The one note that describes an outage: nothing verifies a token, so every one is rejected. */
const OUTAGE_NOTE = "no_token_verifier";

/**
 * What a valid document comes with: what the database does not fit (the server's own sentences, shown as text) under
 * the given heading, and, apart from it, the notes about token verification.
 */
export function warningList(warnings, heading) {
  if (!Array.isArray(warnings) || warnings.length === 0) {
    return null;
  }
  const tokenNotes = warnings.filter((warning) => TOKEN_NOTES.has(warning.code));
  const notes = tokenNotes.filter((warning) => warning.code !== OUTAGE_NOTE);
  const outage = tokenNotes.filter((warning) => warning.code === OUTAGE_NOTE);
  const schema = warnings.filter((warning) => !TOKEN_NOTES.has(warning.code));
  const parts = [];
  if (schema.length > 0) {
    parts.push(h("div", { class: "warn", role: "alert", id: "warnings" }, h("p", {}, heading),
      h("ul", {}, schema.map((warning) => h("li", {}, warning.message)))));
  }
  if (notes.length > 0) {
    parts.push(tokenNotice("note", "token-notes", notes));
  }
  if (outage.length > 0) {
    parts.push(tokenNotice("alert", "token-outage", outage));
  }
  return parts.length === 1 ? parts[0] : h("div", {}, parts);
}

function tokenNotice(role, id, notes) {
  return h("div", { class: "warn", role, id }, h("p", {}, "About how tokens are checked:"),
    h("ul", {}, notes.map((note) => h("li", {}, note.message))));
}

function errorList(errors) {
  return h("ul", {}, errors.map((message) => h("li", {}, message)));
}

function conflictNote(outcome, theirs) {
  if (!theirs) {
    return "Their version could not be shown right now. ";
  }
  return theirs.sourceVersion === outcome.currentVersion ? ""
    : "This replica has not caught up with their save yet, so the text below is older than theirs. ";
}

function conflictView(outcome, theirs, handlers) {
  return h("div", { class: "error", role: "alert" },
    h("p", {}, "Someone else saved first (the source is now at version " + outcome.currentVersion + "). "
      + conflictNote(outcome, theirs) + "Your edits were not saved."),
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
  const line = h("p", { class: outcome.kind === "saved" ? "ok" : "error", role: outcome.kind === "saved" ? "status" : "alert" }, text);
  const warnings = warningList(outcome.warnings, "Saved, but the database does not fit everything in it:");
  return outcome.historyMissed || warnings ? h("div", {}, line, outcome.historyMissed ? historyWarning() : null, warnings) : line;
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

/** What a save or a restore changes. Red: access gained. Amber: a rule or the token settings rewritten. Else plain. */
export function changeList(changes, id) {
  if (changes === null) {
    return h("p", { class: "hint", id }, "The list of changes could not be built.");
  }
  if (changes.length === 0) {
    return h("p", { class: "hint", id }, "No role or table gets or loses an operation.");
  }
  return h("ul", { id, "aria-label": "What this changes" }, changes.map((change) =>
    h("li", { class: "change" + (change.gained.length > 0 ? " gain" : (change.changed.length > 0 || change.other === true ? " modify" : "")) },
      change.text)));
}

/**
 * Asks before a save that loses comments or widens access, and lists what the save changes. The comment notice can be
 * silenced for the session; the access notice never can. The caller closes it and writes.
 */
export function saveDialog(handlers) {
  const remember = handlers.comment ? h("input", { type: "checkbox", id: "rewrite-remember" }) : null;
  return h("dialog", { id: handlers.comment ? "rewrite-warning" : "access-confirm", "aria-describedby": "rewrite-text" },
    h("div", { id: "rewrite-text" },
      handlers.comment ? h("p", {}, "This save rewrites permissions.yaml. Comments and your own layout will be lost.") : null,
      handlers.widens ? h("p", {}, "This save changes who can access your data. Check the list before you save.") : null),
    changeList(handlers.changes, "rewrite-changes"),
    remember ? h("label", { class: "op", for: "rewrite-remember" }, remember, "Don't ask again about comments this session") : null,
    h("div", { class: "actions" },
      h("button", { type: "button", id: "rewrite-save", class: "danger",
        onclick: () => handlers.onConfirm(remember !== null && remember.checked) }, "Save"),
      h("button", { type: "button", id: "rewrite-cancel", class: "secondary", autofocus: true,
        onclick: handlers.onCancel }, "Cancel")));
}
