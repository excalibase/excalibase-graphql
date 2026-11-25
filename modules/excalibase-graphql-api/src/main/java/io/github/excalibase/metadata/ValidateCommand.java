package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code excalibase validate <file> [--project-id ID]}: loads a document exactly as the engine does at start
 * (same parser, same reserved schemas from {@code APP_RESERVED_SCHEMAS}, same project from
 * {@code APP_PROJECT_ID}) and reports the first problem. Exit 0 valid, 1 invalid, 2 bad usage or unreadable.
 */
public final class ValidateCommand {

    public static final String COMMAND = "validate";
    private static final String INVALID = "invalid: ";
    private static final String USAGE = "usage: excalibase validate <file> [--project-id <id>]";

    private ValidateCommand() {
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 2 || !COMMAND.equals(args[0])) {
            err.println(USAGE);
            return 2;
        }
        String projectId = env.get("APP_PROJECT_ID");
        if (args.length == 4 && "--project-id".equals(args[2])) {
            projectId = args[3];
        } else if (args.length != 2) {
            err.println(USAGE);
            return 2;
        }
        boolean legacy = !env.getOrDefault("APP_SECURITY_AUTH_JWKS_URL", "").isBlank()
                || !env.getOrDefault("APP_SECURITY_AUTH_HMAC_SECRET", "").isBlank();
        EngineAuth auth;
        try {
            auth = engineAuth(env, legacy);
        } catch (IllegalStateException e) {
            err.println(INVALID + e.getMessage());
            return 1;
        }
        return validate(Path.of(args[1]), projectId, env.get("APP_RESERVED_SCHEMAS"), auth, env::get, out, err);
    }

    /** The engine's own token settings as the engine reads them, from the same names. */
    private static EngineAuth engineAuth(Map<String, String> env, boolean legacy) {
        EngineAuth appAuth = AuthSettingsReader.fromEnv(env);
        if (appAuth.engine() && legacy) {
            throw new IllegalStateException(AuthSettingsReader.BOTH_REFUSAL);
        }
        return appAuth.engine() ? appAuth : EngineAuth.of(legacy);
    }

    private static int validate(Path file, String projectId, String reserved, EngineAuth auth,
                                UnaryOperator<String> environment, PrintStream out, PrintStream err) {
        String text;
        try {
            text = FileMetadataSource.read(file).text();
        } catch (CharacterCodingException _) {
            err.println(INVALID + file + ": the file is not UTF-8 text");
            return 1;
        } catch (IOException e) {
            err.println("error: cannot read " + file + ": " + e);
            return 2;
        }
        try {
            MetadataDocumentParser.Checked checked = new MetadataDocumentParser(ReservedSchemas.fromConfig(reserved),
                    projectId, auth).checkWithNotes(text, 1, environment);
            PermissionSet permissions = checked.permissions();
            checked.notes().forEach(note -> err.println("warning: " + note.code() + ": " + note.message()));
            out.println("ok: " + file + " (" + permissions.tables().size() + " table rules, "
                    + permissions.functions().size() + " tracked functions)");
            return 0;
        } catch (MetadataDocumentException e) {
            err.println(INVALID + file + ": " + e.getMessage());
            return 1;
        }
    }
}
