package io.github.excalibase.nosql.model;

import java.util.List;

public record IndexDef(List<String> fields, String type, boolean unique) {

    public IndexDef {
        fields = List.copyOf(fields);
        if (type == null) type = "string";
    }
}
