package com.mock.core.config;

/**
 * 单个 Mock Endpoint 的配置模型。
 * path 为精确路径，pathPattern 支持 {variable} 占位符（如 /vals-ap/identity/{type}/sync/{appId}）。
 * 路由注册时优先使用 pathPattern，其次 path。
 */
public class EndpointConfig {

    private String id;
    private String description;
    private String method;       // GET / POST
    private String path;         // 精确路径
    private String pathPattern;  // 路径模式（支持 {variable}）
    private String contentType;
    private String responseContentType;
    private int responseStatus = 200;
    private String responseBody;
    private String responseBodyFile;   // 外部文件路径（classpath: 或 file: 前缀），优先级高于 responseBody
    private long responseDelay = 0;    // 响应延迟最小值（毫秒），0 表示无延迟
    private long responseDelayMax = 0; // 响应延迟最大值（毫秒），> responseDelay 时随机取值
    private String responseScript;     // Nashorn JS 脚本，优先级最高
    private ValidationConfig validation;
    private ConditionalResponse conditionalResponse;

    /** 返回实际用于路由匹配的路径：pathPattern 非空则用它，否则用 path。 */
    public String getEffectivePath() {
        return (pathPattern != null && !pathPattern.isEmpty()) ? pathPattern : path;
    }

    /** 参数校验配置（可选）。为 null 时跳过校验。 */
    public static class ValidationConfig {
        private java.util.List<String> requiredParams = new java.util.ArrayList<>();
        private java.util.List<ParamRule> paramRules = new java.util.ArrayList<>();
        private int errorStatus = 400;
        private String errorBody = "{\"error_no\":\"1001\",\"error_info\":\"参数校验失败\"}";

        public java.util.List<String> getRequiredParams() { return requiredParams; }
        public void setRequiredParams(java.util.List<String> requiredParams) { this.requiredParams = requiredParams; }
        public java.util.List<ParamRule> getParamRules() { return paramRules; }
        public void setParamRules(java.util.List<ParamRule> paramRules) { this.paramRules = paramRules; }
        public int getErrorStatus() { return errorStatus; }
        public void setErrorStatus(int errorStatus) { this.errorStatus = errorStatus; }
        public String getErrorBody() { return errorBody; }
        public void setErrorBody(String errorBody) { this.errorBody = errorBody; }
    }

    /** 参数格式校验规则。name 为参数名，pattern 为正则表达式。 */
    public static class ParamRule {
        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EndpointConfig.class);

        private String name;
        private String pattern;
        private String errorMessage = "参数格式校验失败";
        private transient java.util.regex.Pattern compiledPattern;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public java.util.regex.Pattern getCompiledPattern() { return compiledPattern; }
        public void setCompiledPattern(java.util.regex.Pattern p) { this.compiledPattern = p; }

        /** 按需编译 pattern，当 compiledPattern 为 null 时作为回退方案。 */
        public java.util.regex.Pattern compilePattern() {
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    this.compiledPattern = java.util.regex.Pattern.compile(pattern);
                } catch (java.util.regex.PatternSyntaxException e) {
                    log.warn("ParamRule '{}' 的正则无效: {} — {}", name, pattern, e.getMessage());
                }
            }
            return compiledPattern;
        }
    }

    /** 条件响应配置：根据指定参数的值返回不同响应体。 */
    public static class ConditionalResponse {
        private String param;
        private java.util.Map<String, String> cases = new java.util.LinkedHashMap<>();
        private String defaultResponse;

        public String getParam() { return param; }
        public void setParam(String param) { this.param = param; }
        public java.util.Map<String, String> getCases() { return cases; }
        public void setCases(java.util.Map<String, String> cases) { this.cases = cases; }
        public String getDefaultResponse() { return defaultResponse; }
        public void setDefaultResponse(String defaultResponse) { this.defaultResponse = defaultResponse; }
    }

    // ---------- getters / setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getPathPattern() { return pathPattern; }
    public void setPathPattern(String pathPattern) { this.pathPattern = pathPattern; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getResponseContentType() { return responseContentType; }
    public void setResponseContentType(String responseContentType) { this.responseContentType = responseContentType; }

    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public String getResponseBodyFile() { return responseBodyFile; }
    public void setResponseBodyFile(String responseBodyFile) { this.responseBodyFile = responseBodyFile; }

    public long getResponseDelay() { return responseDelay; }
    public void setResponseDelay(long responseDelay) { this.responseDelay = responseDelay; }

    public long getResponseDelayMax() { return responseDelayMax; }
    public void setResponseDelayMax(long responseDelayMax) { this.responseDelayMax = responseDelayMax; }

    public String getResponseScript() { return responseScript; }
    public void setResponseScript(String responseScript) { this.responseScript = responseScript; }

    public ValidationConfig getValidation() { return validation; }
    public void setValidation(ValidationConfig validation) { this.validation = validation; }

    public ConditionalResponse getConditionalResponse() { return conditionalResponse; }
    public void setConditionalResponse(ConditionalResponse conditionalResponse) { this.conditionalResponse = conditionalResponse; }
}
