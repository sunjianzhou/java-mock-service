package com.mock.core.record;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条录制记录：包含请求参数和响应内容。
 */
public class RecordedExchange {

    private String id;
    private String timestamp;
    private String method;
    private String path;
    private String endpointId;
    private Map<String, String> requestParams = new LinkedHashMap<>();
    private int responseStatus;
    private String responseContentType;
    private String responseBody;
    private long responseDelay;

    public RecordedExchange() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }

    public Map<String, String> getRequestParams() { return requestParams; }
    public void setRequestParams(Map<String, String> requestParams) { this.requestParams = requestParams; }

    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseContentType() { return responseContentType; }
    public void setResponseContentType(String responseContentType) { this.responseContentType = responseContentType; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public long getResponseDelay() { return responseDelay; }
    public void setResponseDelay(long responseDelay) { this.responseDelay = responseDelay; }
}
