// The only place the admin secret lives and the only place it is sent: the request header, to this origin's
// /_admin/v1. It is kept in sessionStorage so a reload of the tab keeps the session; closing the tab ends it.

const HEADER = "X-Excalibase-Admin-Secret";
const BASE = "/_admin/v1";
const KEY = "excalibase.console.session";

let secret = readStored();

function readStored() {
  try {
    return window.sessionStorage.getItem(KEY) || "";
  } catch {
    return "";
  }
}

function store(value) {
  secret = value;
  try {
    if (value) {
      window.sessionStorage.setItem(KEY, value);
    } else {
      window.sessionStorage.removeItem(KEY);
    }
  } catch {
    // the secret then lives in memory only, for this page load
  }
}

export function signedIn() {
  return secret !== "";
}

export function signOut() {
  store("");
}

export class ApiError extends Error {
  constructor(status, code, body) {
    super(code);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function codeOf(status, parsed) {
  if (parsed && parsed.error) {
    return String(parsed.error);
  }
  return parsed && Array.isArray(parsed.errors) ? "invalid_document" : "error_" + status;
}

async function call(method, path, body, contentType, extraHeaders = {}) {
  const headers = { ...extraHeaders, [HEADER]: secret };
  if (body !== undefined) {
    headers["Content-Type"] = contentType;
  }
  const response = await fetch(BASE + path, { method, headers, body, credentials: "omit", cache: "no-store",
    referrerPolicy: "no-referrer" });
  const parsed = await parse(response);
  if (response.status === 401) {
    signOut();
  }
  if (!response.ok) {
    throw new ApiError(response.status, codeOf(response.status, parsed), parsed);
  }
  return parsed;
}

async function parse(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

/** Opens a session: the secret is checked against the admin API and kept only if it is accepted. */
export async function signIn(candidate) {
  store(candidate);
  try {
    await call("GET", "/replicas");
  } catch (failure) {
    store("");
    throw failure;
  }
}

export async function getMetadata() {
  const reply = await call("GET", "/metadata");
  return { ...reply, text: reply.document };
}

/** Saves the text if the source still holds the version it was loaded at (the quoted tag of If-Match). */
export const putMetadata = (text, sourceVersion) =>
  call("PUT", "/metadata", text, "text/plain", { "If-Match": '"' + sourceVersion + '"' });

export const getSchema = () => call("GET", "/schema");
export const getReplicas = () => call("GET", "/replicas");
export const validateText = (text) => call("POST", "/validate", text, "text/plain");
export const parseText = (text) => call("POST", "/parse", text, "text/plain");

export const getHistory = () => call("GET", "/history");
export const getHistoryEntry = (id) => call("GET", "/history/" + encodeURIComponent(id));

/** Puts an earlier version back: a save of its text, conditional on the version the page started from. */
export const restoreHistory = (id, sourceVersion) =>
  call("POST", "/history/" + encodeURIComponent(id) + "/restore", undefined, undefined, { "If-Match": '"' + sourceVersion + '"' });
