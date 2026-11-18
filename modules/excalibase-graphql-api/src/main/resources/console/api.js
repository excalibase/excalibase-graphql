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
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

async function call(method, path, body, contentType) {
  const headers = { [HEADER]: secret };
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
    throw new ApiError(response.status, parsed && parsed.error ? String(parsed.error) : "error_" + response.status);
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

export const getMetadata = () => call("GET", "/metadata");
export const getSchema = () => call("GET", "/schema");
export const getReplicas = () => call("GET", "/replicas");
export const validateDocument = (text) => call("POST", "/validate", text, "text/plain");
