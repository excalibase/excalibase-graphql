package io.github.excalibase.exception.model;

public class ApplicationExceptionResponse {
    private String code;
    private String key;
    private String[] details;

    private ApplicationExceptionResponse(Builder builder) {
        this.code = builder.code;
        this.key = builder.key;
        this.details = builder.details;
    }

    public ApplicationExceptionResponse() {
    }

    public ApplicationExceptionResponse(String code, String key, String[] details) {
        this.code = code;
        this.key = key;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String[] getDetails() {
        return details;
    }

    public void setDetails(String[] details) {
        this.details = details;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String code;
        private String key;
        private String[] details;

        private Builder() {
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder details(String... details) {
            this.details = details;
            return this;
        }

        public ApplicationExceptionResponse build() {
            return new ApplicationExceptionResponse(this);
        }
    }
}
