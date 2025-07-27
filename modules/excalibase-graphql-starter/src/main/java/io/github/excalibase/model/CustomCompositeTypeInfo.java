package io.github.excalibase.model;

import java.util.List;
import java.util.ArrayList;

/**
 * Information about a custom PostgreSQL composite type.
 * Contains the type name and its attributes.
 */
public class CustomCompositeTypeInfo {
    private String name;
    private String schema;
    private List<CompositeTypeAttribute> attributes;

    public CustomCompositeTypeInfo() {
        this.attributes = new ArrayList<>();
    }

    public CustomCompositeTypeInfo(String name, String schema, List<CompositeTypeAttribute> attributes) {
        this.name = name;
        this.schema = schema;
        this.attributes = attributes != null ? new ArrayList<>(attributes) : new ArrayList<>();
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

    public List<CompositeTypeAttribute> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<CompositeTypeAttribute> attributes) {
        this.attributes = attributes != null ? new ArrayList<>(attributes) : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "CustomCompositeTypeInfo{" +
                "name='" + name + '\'' +
                ", schema='" + schema + '\'' +
                ", attributes=" + attributes +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CustomCompositeTypeInfo that = (CustomCompositeTypeInfo) o;

        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        if (schema != null ? !schema.equals(that.schema) : that.schema != null) return false;
        return attributes != null ? attributes.equals(that.attributes) : that.attributes == null;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (schema != null ? schema.hashCode() : 0);
        result = 31 * result + (attributes != null ? attributes.hashCode() : 0);
        return result;
    }
} 