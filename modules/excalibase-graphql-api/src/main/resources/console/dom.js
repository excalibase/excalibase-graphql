// Builds elements from text only. Server data reaches the page through text nodes, never as markup.

const ATTRIBUTES = new Set(["class", "type", "id", "for", "rows", "placeholder", "autocomplete", "disabled",
  "role", "aria-selected", "aria-live", "aria-label", "aria-pressed", "spellcheck", "name", "checked", "value",
  "title", "colspan"]);

export function h(tag, props = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(props)) {
    if (name.startsWith("on") && typeof value === "function") {
      element.addEventListener(name.slice(2), value);
    } else if (ATTRIBUTES.has(name) && value !== false && value != null) {
      element.setAttribute(name, value === true ? "" : String(value));
    }
  }
  append(element, children);
  return element;
}

function append(parent, children) {
  for (const child of children.flat()) {
    if (child == null || child === false) {
      continue;
    }
    parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function rootElement() {
  return document.getElementById("root");
}

/** Puts a built <dialog> on the page and opens it as a modal. */
export function showModal(root, dialog) {
  root.append(dialog);
  dialog.showModal();
}

export function mount(root, ...children) {
  root.replaceChildren();
  append(root, children);
}

/**
 * Hands the browser a file. The text becomes a Blob and the link points at that Blob's own URL, so no address from
 * the server is ever used and nothing is navigated to. The file name is chosen by the console, never by the data.
 */
export function saveAs(filename, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.setAttribute("href", url);
  link.setAttribute("download", filename);
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
