package io.github.excalibase.permissions;

/** What a preset column is filled with: a literal JSON value, or a session variable bound per request. */
public sealed interface PresetValue permits Literal, SessionVariable {
}
