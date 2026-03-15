package io.github.excalibase.model;

import java.util.List;

/**
 * Represents a stored procedure discovered from the database.
 */
public class StoredProcedureInfo {

    private String name;
    private String schema;
    private List<ProcedureParam> parameters;

    public StoredProcedureInfo() {}

    public StoredProcedureInfo(String name, String schema, List<ProcedureParam> parameters) {
        this.name = name;
        this.schema = schema;
        this.parameters = parameters;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public List<ProcedureParam> getParameters() { return parameters; }
    public void setParameters(List<ProcedureParam> parameters) { this.parameters = parameters; }

    /**
     * Represents a single parameter of a stored procedure.
     */
    public static class ProcedureParam {
        private String name;
        private String dataType;
        private String mode;  // "IN", "OUT", "INOUT"
        private int position;

        public ProcedureParam() {}

        public ProcedureParam(String name, String dataType, String mode, int position) {
            this.name = name;
            this.dataType = dataType;
            this.mode = mode;
            this.position = position;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }

        public boolean isIn() { return "IN".equalsIgnoreCase(mode) || "INOUT".equalsIgnoreCase(mode); }
        public boolean isOut() { return "OUT".equalsIgnoreCase(mode) || "INOUT".equalsIgnoreCase(mode); }
    }
}
