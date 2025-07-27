package io.github.excalibase.model;

/**
 * Information about an attribute of a custom PostgreSQL composite type.
 * Contains the attribute name, type, order, and nullability.
 */
public class CompositeTypeAttribute {
    private String name;
    private String type;
    private int order;
    private boolean nullable;

    public CompositeTypeAttribute() {
    }

    public CompositeTypeAttribute(String name, String type, int order, boolean nullable) {
        this.name = name;
        this.type = type;
        this.order = order;
        this.nullable = nullable;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    @Override
    public String toString() {
        return "CompositeTypeAttribute{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", order=" + order +
                ", nullable=" + nullable +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CompositeTypeAttribute that = (CompositeTypeAttribute) o;

        if (order != that.order) return false;
        if (nullable != that.nullable) return false;
        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        return type != null ? type.equals(that.type) : that.type == null;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + order;
        result = 31 * result + (nullable ? 1 : 0);
        return result;
    }
} 