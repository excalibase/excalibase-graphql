// Builds elements from text only. Server data reaches the page through text nodes, never as markup.

const ATTRIBUTES = new Set(["class", "type", "id", "for", "rows", "placeholder", "autocomplete", "disabled",
  "role", "aria-selected", "aria-live", "spellcheck", "name"]);

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

export function mount(root, ...children) {
  root.replaceChildren();
  append(root, children);
}
