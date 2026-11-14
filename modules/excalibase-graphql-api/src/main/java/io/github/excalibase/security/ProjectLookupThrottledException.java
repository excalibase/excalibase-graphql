package io.github.excalibase.security;

/** Too many projects the engine had not yet resolved were asked for at once: answered 429 without a vault call. */
public class ProjectLookupThrottledException extends RuntimeException {

    public ProjectLookupThrottledException() {
        super("Too many project lookups");
    }
}
