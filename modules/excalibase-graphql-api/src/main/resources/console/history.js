import { h } from "/console/dom.js";
import { lineDiff, withContext } from "/console/diff.js";

const MARKS = { add: "+ ", del: "- ", same: "  " };

/** The time as the engine saved it, in UTC, so two people read the same list. */
export function shortTime(iso) {
  return String(iso).replace("T", " ").slice(0, 19) + " UTC";
}

function diffLine(entry) {
  if (entry.type === "gap") {
    return h("div", { class: "line gap" }, "... " + entry.count + " unchanged lines");
  }
  return h("div", { class: "line " + entry.type }, MARKS[entry.type] + entry.text);
}

/** The old version against the document the engine serves now. */
function diffView(entry, current) {
  const lines = lineDiff(entry.text, current);
  if (lines.every((line) => line.type === "same")) {
    return h("p", { id: "diff-same" }, "This version is the same as the text served now.");
  }
  return h("div", {},
    h("p", { class: "hint" }, "Lines marked - are only in this old version. Lines marked + are only in the text served now."),
    h("pre", { id: "diff", class: "diff" }, withContext(lines).map(diffLine)));
}

function selectedView(selected, handlers) {
  return h("section", { id: "history-entry" },
    h("h2", {}, "Version replaced at " + shortTime(selected.entry.at)),
    diffView(selected.entry, selected.current),
    h("div", { class: "actions" },
      h("button", { type: "button", id: "restore", onclick: () => handlers.onRestore(selected.entry) }, "Restore this version")));
}

function noteView(note) {
  if (!note) {
    return null;
  }
  if (note.kind === "ok") {
    return h("p", { class: "ok", role: "status" }, note.text);
  }
  return h("div", { class: "error", role: "alert" }, h("p", {}, note.text),
    note.errors ? h("ul", {}, note.errors.map((message) => h("li", {}, message))) : null);
}

function listView(entries, selectedId, handlers) {
  const rows = entries.map((entry) => h("tr", { class: entry.id === selectedId ? "selected" : "" },
    h("td", {}, shortTime(entry.at)),
    h("td", {}, entry.hash.slice(0, 12)),
    h("td", {}, entry.size + " bytes"),
    h("td", {}, h("button", { type: "button", class: "secondary", "aria-label": "View the version replaced at " + shortTime(entry.at),
      onclick: () => handlers.onSelect(entry.id) }, "View"))));
  return h("div", { class: "scroll" }, h("table", { id: "history-list" },
    h("thead", {}, h("tr", {}, h("th", {}, "Replaced at"), h("th", {}, "Version"), h("th", {}, "Size"), h("th", {}, ""))),
    h("tbody", {}, rows)));
}

/**
 * The History tab: the versions a save replaced, newest first, and one of them against the text served now.
 * A source that keeps no history says so.
 */
export function historyView(model, handlers) {
  if (!model.list.available) {
    return h("p", { id: "history-unavailable" }, "History is available for the file source.");
  }
  const entries = model.list.entries;
  return h("section", {},
    h("p", { class: "hint" }, "Each save adds the text it replaced, comments included. The last 20 versions are kept. "
      + "Edits made to the file outside the console are not recorded."),
    noteView(model.note),
    entries.length === 0 ? h("p", {}, "Nothing has been replaced yet.") : listView(entries, model.selected && model.selected.entry.id, handlers),
    model.selected ? selectedView(model.selected, handlers) : null);
}

/** Asks before a version replaces the text served now. The caller closes it and restores. */
export function restoreDialog(entry, dirty, handlers) {
  return h("dialog", { id: "restore-confirm", "aria-describedby": "restore-text" },
    h("p", { id: "restore-text" }, "Restore this version, replaced at " + shortTime(entry.at) + "? The document served now is added to "
      + "the history, so you can undo this."),
    dirty ? h("p", { class: "error" }, "Your unsaved edits in the editor will be discarded.") : null,
    h("div", { class: "actions" },
      h("button", { type: "button", id: "restore-yes", class: "danger", onclick: handlers.onConfirm }, "Restore"),
      h("button", { type: "button", id: "restore-no", class: "secondary", autofocus: true, onclick: handlers.onCancel }, "Cancel")));
}

/** What a refused restore says, from the answer's status; the reasons of a 422 are the engine's own. */
export function restoreFailure(failure) {
  const body = failure.body || {};
  switch (failure.status) {
    case 409:
      return { kind: "error", text: "Someone else saved first, so nothing was restored. Reload the page to see their version." };
    case 422:
      return { kind: "error", text: "Not restored: this version no longer passes the engine's checks.",
        errors: Array.isArray(body.errors) ? body.errors : [] };
    case 404:
      return { kind: "error", text: "That version is no longer in the history." };
    case 405:
      return { kind: "error", text: "This source is read-only. Nothing was restored." };
    default:
      return { kind: "error", text: "The restore failed (" + failure.code + "). The last good document is still served." };
  }
}
