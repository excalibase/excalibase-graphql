/** What each reason "test a token" can answer means, in plain words. The engine sends a name, never a detail. */
export const REASON_TEXT: Record<string, string> = {
  expired: 'The token has expired. Sign in again to get a new one.',
  not_yet_valid: 'The token is not valid yet: its start time (nbf) is in the future. Check the clocks.',
  bad_signature: 'The signature does not match the key or secret the engine uses.',
  algorithm: 'The token is signed with an algorithm the engine does not accept (or is not signed at all).',
  issuer: 'The token comes from a different issuer than the one the engine expects.',
  audience: 'The token was made for a different audience than the one the engine expects.',
  role_claim: 'The role claim is missing, not a role name, or not one this token may act as.',
  missing_user_id: 'The claim that holds the user id is missing from the token.',
  malformed: 'This is not a readable token.',
  unsupported_token_type: 'This is a refresh or key-management token. Use the access token.',
  no_verifier: 'The engine has no token settings, so it rejects every token.',
};

export const REASON_UNKNOWN = 'The token was refused.';

/** The notes about how tokens are checked, by the code the engine gives them. */
export const NOTE_TEXT: Record<string, string> = {
  jwt_deprecated: "The permissions document holds its own jwt: section. That is deprecated: move it to the engine's token settings (APP_AUTH_*).",
  jwt_identical_remove_it: "The jwt: section in the document says the same as the engine's token settings (APP_AUTH_*). Once every replica runs this version, you can remove it.",
  no_token_verifier: 'Nothing verifies a token: the document has rules for a role other than anon, but no token settings are configured. Every token is rejected.',
};

export const SOURCE_TEXT: Record<string, string> = {
  engine: "The engine's own settings (APP_AUTH_*)",
  'legacy-engine': 'The older engine settings (APP_SECURITY_AUTH_*)',
  document: "A jwt: section in the permissions document (deprecated)",
  none: 'None: no token is accepted',
};
