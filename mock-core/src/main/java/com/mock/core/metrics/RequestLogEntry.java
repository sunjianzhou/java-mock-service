package com.mock.core.metrics;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 单次请求的轻量日志记录，供统计面板和最近请求列表使用。 */
public class RequestLogEntry {

    private final String id        = UUID.randomUUID().toString().substring(0, 8);
    private final String timestamp = Instant.now().toString();
    private String method;
    private String path;
    private String endpointId;
    private Map<String, String> params;
    private int    status;
    private long   durationMs;
    private String responsePreview; // 响应体前 200 字符

    public String getId()              { return id; }
    public String getTimestamp()       { return timestamp; }
    public String getMethod()          { return method; }
    public void   setMethod(String v)  { this.method = v; }
    public String getPath()            { return path; }
    public void   setPath(String v)    { this.path = v; }
    public String getEndpointId()      { return endpointId; }
    public void   setEndpointId(String v) { this.endpointId = v; }
    public Map<String, String> getParams()       { return params; }
    public void   setParams(Map<String, String> v) { this.params = v; }
    public int    getStatus()          { return status; }
    public void   setStatus(int v)     { this.status = v; }
    public long   getDurationMs()      { return durationMs; }
    public void   setDurationMs(long v){ this.durationMs = v; }
    public String getResponsePreview() { return responsePreview; }
    public void   setResponsePreview(String v) { this.responsePreview = v; }
}
