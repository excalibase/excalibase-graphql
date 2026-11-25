import { h } from "/console/dom.js";
import { changeList, historyWarning, warningList } from "/console/editor.js";
import { widens } from "/console/docmodel.js";
import { lineDiff, withContext } from "/console/diff.js";

const MAX_DRAWN = 5000;
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

/** The old version against the text served now. A diff that would draw too many lines is not drawn. */
function diffView(entry, current, handlers) {
  const lines = lineDiff(entry.text, current);
  if (lines.every((line) => line.type === "same")) {
    return h("p", { id: "diff-same" }, "This version is the same as the text served now.");
  }
  const shown = withContext(lines);
  if (shown.length > MAX_DRAWN) {
    return h("div", { id: "diff-large" },
      h("p", {}, "This diff is too large to show here. Save both versions as files and compare them there."),
      h("div", { class: "actions" },
        h("button", { type: "button", id: "save-old", class: "secondary", onclick: () => handlers.onSaveFile("old", entry.text) },
          "Save this version as a file"),
        h("button", { type: "button", id: "save-current", class: "secondary", onclick: () => handlers.onSaveFile("current", current) },
          "Save the served text as a file")));
  }
  return h("div", {},
    h("p", { class: "hint" }, "Lines marked - are only in this old version. Lines marked + are only in the text served now."),
    h("pre", { id: "diff", class: "diff" }, shown.map(diffLine)));
}

function selectedView(selected, handlers) {
  return h("section", { id: "history-entry" },
    h("h2", {}, "Version replaced at " + shortTime(selected.entry.at)),
    diffView(selected.entry, selected.current, handlers),
    h("div", { class: "actions" },
      h("button", { type: "button", id: "restore", onclick: () => handlers.onRestore(selected.entry) }, "Restore this version")));
}

function noteView(note) {
  if (!note) {
    return null;
  }
  if (note.kind === "ok") {
    return h("div", {}, h("p", { class: "ok", role: "status" }, note.text), note.historyMissed ? historyWarning() : null,
      warningList(note.warnings, "Restored, but the database does not fit everything in it:"));
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
  if (model.list.readable === false) {
    return h("p", { id: "history-unreadable", class: "error", role: "alert" },
      "The history could not be read, so no versions are listed. Saves still go ahead; check the file next to permissions.yaml.");
  }
  const entries = model.list.entries;
  return h("section", {},
    h("p", { class: "hint" }, "Each save adds the text it replaced, comments included. The last 20 versions are kept. "
      + "Edits made to the file outside the console are not recorded."),
    noteView(model.note),
    entries.length === 0 ? h("p", {}, "Nothing has been replaced yet.") : listView(entries, model.selected && model.selected.entry.id, handlers),
    model.selected ? selectedView(model.selected, handlers) : null);
}

/** Asks before a version replaces the text served now, and lists what that changes: a re-granted access is red. */
export function restoreDialog(entry, dirty, changes, handlers) {
  return h("dialog", { id: "restore-confirm", "aria-describedby": "restore-text" },
    h("p", { id: "restore-text" }, "Restore this version, replaced at " + shortTime(entry.at) + "? The text served now is added to "
      + "the history, so you can undo this."),
    changes === null || widens(changes)
      ? h("p", { class: "warn" }, "This restore changes who can access your data. Check the list before you restore.") : null,
    changeList(changes, "restore-changes"),
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
