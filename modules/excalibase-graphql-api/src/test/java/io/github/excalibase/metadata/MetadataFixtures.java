package io.github.excalibase.metadata;

/** Documents the metadata tests load; one place so the parser, the schema and the CLI agree on "good" and "bad". */
final class MetadataFixtures {

    static final String PROJECT = "demo";

    private MetadataFixtures() {
    }

    static final String GOOD_YAML = """
            format: 1
            projectId: demo
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    /** Differs from {@link #GOOD_YAML} by one more rule. */
    static final String GOOD_YAML_TWO_RULES = GOOD_YAML + """
              - table: public.secrets
                role: admin
                select:
                  filter: {}
                  columns: ["id"]
            """;

    static final String GOOD_JSON = """
            {"format":1,"projectId":"demo","tables":[{"table":"public.orders","role":"user",
             "select":{"filter":{},"columns":"*"}}],"functions":[],"functionPermissions":[]}
            """;

    static final String WITH_JWT = """
            format: 1
            projectId: demo
            jwt:
              issuer: https://tenant.example.com/
              audience: https://api.demo
              jwksUrl: https://tenant.example.com/.well-known/jwks.json
              algorithms: [RS256]
              claims:
                role: https://demo/role
                defaultRole: user
                userId: sub
                extra:
                  org-id: https://demo/org_id
            tables: []
            """;

    static final String BAD_SYNTAX = "format: 1\nprojectId: [unclosed\n";

    static final String BAD_UNKNOWN_KEY = GOOD_YAML + "surprise: true\n";

    static final String BAD_RULE = """
            format: 1
            projectId: demo
            tables:
              - table: public.orders
                role: user
                select:
                  columns: "*"
            """;

    static final String BAD_RESERVED = """
            format: 1
            projectId: demo
            tables:
              - table: excalibase_meta.metadata_documents
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    static String withProject(String project) {
        return GOOD_YAML.replace("projectId: demo", "projectId: " + project);
    }
}
