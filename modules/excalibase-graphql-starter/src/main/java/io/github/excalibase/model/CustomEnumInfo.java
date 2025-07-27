package io.github.excalibase.model;

import java.util.List;
import java.util.ArrayList;

/**
 * Information about a custom PostgreSQL enum type.
 * Contains the enum name and its possible values.
 */
public class CustomEnumInfo {
    private String name;
    private String schema;
    private List<String> values;

    public CustomEnumInfo() {
        this.values = new ArrayList<>();
    }

    public CustomEnumInfo(String name, String schema, List<String> values) {
        this.name = name;
        this.schema = schema;
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "CustomEnumInfo{" +
                "name='" + name + '\'' +
                ", schema='" + schema + '\'' +
                ", values=" + values +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CustomEnumInfo that = (CustomEnumInfo) o;

        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        if (schema != null ? !schema.equals(that.schema) : that.schema != null) return false;
        return values != null ? values.equals(that.values) : that.values == null;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (schema != null ? schema.hashCode() : 0);
        result = 31 * result + (values != null ? values.hashCode() : 0);
        return result;
    }
} 