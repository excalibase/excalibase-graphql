import { h, mount } from "/console/dom.js";

const SEGMENTS = 3;
const SKIPPED = new Set(["iss", "aud", "exp", "iat", "nbf", "jti"]);
const EXTRA_NAME = /[^a-z0-9_-]+/g;

function decodePart(part) {
  const padded = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

/** Reads a token's claims in the browser. The signature is not checked here, and the token is not kept anywhere. */
export function decodeToken(token) {
  const parts = token.trim().split(".");
  if (parts.length !== SEGMENTS) {
    throw new Error("A token has three parts separated by dots.");
  }
  try {
    const claims = decodePart(parts[1]);
    if (claims === null || typeof claims !== "object" || Array.isArray(claims)) {
      throw new Error("claims");
    }
    return claims;
  } catch {
    throw new Error("The middle part of that token is not readable claims.");
  }
}

/** Every claim as a dotted path with a short preview, the registered ones last so the useful ones come first. */
export function claimPaths(claims, prefix = "") {
  const found = [];
  for (const [name, value] of Object.entries(claims)) {
    const path = prefix + name;
    if (value !== null && typeof value === "object" && !Array.isArray(value)) {
      found.push(...claimPaths(value, path + "."));
    } else {
      found.push({ path, preview: JSON.stringify(value) });
    }
  }
  return found.sort((a, b) => Number(SKIPPED.has(a.path)) - Number(SKIPPED.has(b.path)));
}

export function extraName(path) {
  const last = path.split(".").pop().toLowerCase().replace(EXTRA_NAME, "-").replace(/^[-_]+/, "");
  return last.slice(0, 63);
}

const text = (value) => (typeof value === "string" ? value.trim() : "");

/** The {@code jwt:} section from the form, with only what was filled in. Whatever the engine would refuse, it says. */
export function buildSection(form) {
  const claims = {};
  for (const key of ["role", "defaultRole", "userId"]) {
    if (text(form[key]) !== "") {
      claims[key] = text(form[key]);
    }
  }
  if (Object.keys(form.extra || {}).length > 0) {
    claims.extra = { ...form.extra };
  }
  const section = {};
  for (const key of ["issuer", "jwksUrl", "secret"]) {
    if (text(form[key]) !== "") {
      section[key] = text(form[key]);
    }
  }
  const audiences = text(form.audience).split(",").map((audience) => audience.trim()).filter(Boolean);
  if (audiences.length > 0) {
    section.audience = audiences.length === 1 ? audiences[0] : audiences;
  }
  return Object.keys(claims).length > 0 ? { ...section, claims } : section;
}

/** The form a saved section fills in, so an existing setup can be changed rather than typed again. */
export function formFrom(section) {
  const claims = (section && section.claims) || {};
  const audience = section && section.audience;
  return {
    issuer: (section && section.issuer) || "", jwksUrl: (section && section.jwksUrl) || "",
    secret: (section && section.secret) || "",
    audience: Array.isArray(audience) ? audience.join(", ") : audience || "",
    role: claims.role || "", defaultRole: claims.defaultRole || "", userId: claims.userId || "",
    extra: { ...(claims.extra || {}) }
  };
}

function labelled(id, label, form, key, onChange) {
  return h("div", { class: "row" }, h("label", { for: id }, label),
    h("input", { type: "text", id, autocomplete: "off", spellcheck: "false", value: form[key],
      oninput: (event) => onChange(key, event.target.value) }));
}

function claimRow(claim, pick) {
  return h("tr", {}, h("td", {}, claim.path), h("td", {}, claim.preview),
    h("td", {}, h("button", { type: "button", class: "secondary", onclick: () => pick("role", claim.path) }, "Role"),
      h("button", { type: "button", class: "secondary", onclick: () => pick("userId", claim.path) }, "User id"),
      h("button", { type: "button", class: "secondary", onclick: () => pick("extra", claim.path) }, "Extra")));
}

function extraList(form, remove) {
  const names = Object.keys(form.extra);
  return names.length === 0 ? h("p", { class: "hint" }, "No extra claims are mapped.") : h("ul", {},
    names.map((name) => h("li", {}, "X-Excalibase-" + name + " from " + form.extra[name] + " ",
      h("button", { type: "button", class: "link", onclick: () => remove(name) }, "Remove"))));
}

/**
 * The sample-token flow: paste a token, see its claims, click which one is the role and which the user id, and
 * fill in the issuer, audience and key source. The token lives in this closure for the life of the view only.
 */
export function jwtView(section, onApply) {
  const container = h("section", {});
  const state = { form: formFrom(section), claims: [], problem: null };
  const token = h("textarea", { id: "sample-token", rows: 4, spellcheck: "false", autocomplete: "off",
    placeholder: "Paste a sample token. It is read here and never sent or kept." });
  const change = (key, value) => { state.form = { ...state.form, [key]: value }; };
  const pick = (key, path) => {
    state.form = key === "extra"
      ? { ...state.form, extra: { ...state.form.extra, [extraName(path)]: path } }
      : { ...state.form, [key]: path };
    draw();
  };
  const decode = () => {
    try {
      state.claims = claimPaths(decodeToken(token.value));
      state.problem = null;
    } catch (failure) {
      state.claims = [];
      state.problem = failure.message;
    }
    token.value = "";
    draw();
  };
  const removeExtra = (name) => {
    state.form = { ...state.form, extra: Object.fromEntries(Object.entries(state.form.extra).filter(([key]) => key !== name)) };
    draw();
  };
  function draw() {
    mount(container, ...sections(token, state, { change, pick, decode, removeExtra, apply: () => onApply(buildSection(state.form)) }));
  }
  draw();
  return container;
}

function sections(token, state, actions) {
  const { form, claims, problem } = state;
  return [
    h("h2", {}, "Sample token"), token,
    h("button", { type: "button", id: "decode", onclick: actions.decode }, "Read claims"),
    problem ? h("p", { class: "error", role: "alert" }, problem) : null,
    claims.length === 0 ? null : h("table", {}, h("thead", {}, h("tr", {}, h("th", {}, "Claim"), h("th", {}, "Value"),
      h("th", {}, "Use as"))), h("tbody", {}, claims.map((claim) => claimRow(claim, actions.pick)))),
    h("h2", {}, "Token settings"),
    labelled("jwt-role", "Role claim", form, "role", actions.change),
    labelled("jwt-default-role", "Default role (when the role claim is a list)", form, "defaultRole", actions.change),
    labelled("jwt-user", "User id claim", form, "userId", actions.change),
    extraList(form, actions.removeExtra),
    labelled("jwt-issuer", "Issuer", form, "issuer", actions.change),
    labelled("jwt-audience", "Audience", form, "audience", actions.change),
    labelled("jwt-jwks", "JWKS URL", form, "jwksUrl", actions.change),
    labelled("jwt-secret", "Secret reference (env:NAME or file:PATH, never the secret)", form, "secret", actions.change),
    h("button", { type: "button", id: "apply-jwt", onclick: actions.apply }, "Apply to the document")
  ];
}
