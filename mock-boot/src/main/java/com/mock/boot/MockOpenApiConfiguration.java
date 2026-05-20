package com.mock.boot;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockOpenApiConfiguration {

    @Bean
    public OpenAPI mockOpenAPI(MockConfigProperties configProperties) {
        Paths paths = new Paths();
        for (EndpointConfig ep : configProperties.getEndpoints()) {
            PathItem pathItem = buildPathItem(ep);
            String openApiPath = ep.getPathPattern() != null
                ? ep.getPathPattern()
                : ep.getPath();
            paths.addPathItem(openApiPath, pathItem);
        }

        return new OpenAPI()
            .info(new Info()
                .title("Mock Service API")
                .description("Mock 服务自动生成的 API 文档——所有接口均返回预设 Mock 响应")
                .version("1.0.0"))
            .paths(paths);
    }

    private PathItem buildPathItem(EndpointConfig ep) {
        Operation operation = new Operation()
            .operationId(ep.getId())
            .summary(ep.getDescription() != null ? ep.getDescription() : ep.getId());

        // request body
        if (ep.getContentType() != null && !"GET".equalsIgnoreCase(ep.getMethod())) {
            operation.requestBody(buildRequestBody(ep));
        }

        // responses
        operation.responses(buildResponses(ep));

        PathItem pathItem = new PathItem();
        switch (ep.getMethod().toUpperCase()) {
            case "GET":
                pathItem.get(operation);
                break;
            case "POST":
                pathItem.post(operation);
                break;
            case "PUT":
                pathItem.put(operation);
                break;
            case "DELETE":
                pathItem.delete(operation);
                break;
            default:
                pathItem.post(operation);
        }
        return pathItem;
    }

    private RequestBody buildRequestBody(EndpointConfig ep) {
        String contentType = ep.getContentType();
        Schema<?> schema = buildRequestSchema(ep, contentType);

        Content content = new Content()
            .addMediaType(contentType, new MediaType().schema(schema));

        return new RequestBody().content(content);
    }

    private Schema<?> buildRequestSchema(EndpointConfig ep, String contentType) {
        if (contentType.contains("x-www-form-urlencoded")) {
            ObjectSchema formSchema = new ObjectSchema();
            if (ep.getValidation() != null && ep.getValidation().getRequiredParams() != null) {
                for (String param : ep.getValidation().getRequiredParams()) {
                    formSchema.addProperty(param, new StringSchema());
                }
            }
            return formSchema;
        }
        if (contentType.contains("json")) {
            ObjectSchema jsonSchema = new ObjectSchema();
            if (ep.getValidation() != null && ep.getValidation().getRequiredParams() != null) {
                for (String param : ep.getValidation().getRequiredParams()) {
                    jsonSchema.addProperty(param, new StringSchema());
                }
            }
            return jsonSchema;
        }
        return new StringSchema();
    }

    private ApiResponses buildResponses(EndpointConfig ep) {
        ApiResponses responses = new ApiResponses();

        int successStatus = ep.getResponseStatus() > 0 ? ep.getResponseStatus() : 200;
        String successContentType = ep.getResponseContentType() != null
            ? ep.getResponseContentType()
            : "application/json";

        ApiResponse successResponse = new ApiResponse()
            .description("成功响应")
            .content(new Content()
                .addMediaType(successContentType,
                    new MediaType().schema(new StringSchema().example(ep.getResponseBody()))));

        responses.addApiResponse(String.valueOf(successStatus), successResponse);

        if (ep.getValidation() != null) {
            int errorStatus = ep.getValidation().getErrorStatus() > 0
                ? ep.getValidation().getErrorStatus()
                : 400;
            String errorBody = ep.getValidation().getErrorBody();
            if (errorBody != null) {
                ApiResponse errorResponse = new ApiResponse()
                    .description("校验失败")
                    .content(new Content()
                        .addMediaType("application/json",
                            new MediaType().schema(new StringSchema().example(errorBody))));
                responses.addApiResponse(String.valueOf(errorStatus), errorResponse);
            }
        }

        return responses;
    }
}
