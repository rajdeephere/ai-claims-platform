package com.claimsai.common.openapi;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.common.error.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI definition for {@code /api/v1/**}, published at {@code /v3/api-docs/v1} and checked against the
 * committed contract in docs/openapi (ADR-0008). Every operation documents the shared ApiError schema, the
 * correlation header and its error statuses; secured operations require the bearer token.
 */
@Configuration
public class OpenApiConfig {

    public static final String GROUP = "v1";
    private static final String BEARER = "bearerAuth";
    private static final String CORRELATION_PARAM = "CorrelationId";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ApiError";

    private static final Map<Integer, String> ERROR_DESCRIPTIONS = Map.of(
            400, "Invalid request: malformed body, bad parameter or failed validation (see `violations`)",
            401, "Missing, invalid or expired access token",
            403, "The user's role does not allow this action",
            404, "Not found, or not visible to this user",
            409, "Conflicts with the current state: invalid transition, duplicate or concurrent update",
            412, "`If-Match` does not match the current version: reload and retry",
            422, "Well-formed, but breaks a business rule",
            429, "Too many requests",
            500, "Unexpected error (details are logged with the correlation ID, never returned)");

    @Bean
    public OpenAPI claimsOpenApi(@Value("${app.api.version:1.0.0}") String version,
                                 @Value("${app.api.server-url:http://localhost:8081}") String serverUrl) {
        Components components = new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login (valid 15 minutes)"))
                .addParameters(CORRELATION_PARAM, new HeaderParameter()
                        .name(CorrelationId.HEADER)
                        .required(false)
                        .description("Optional trace ID; generated if absent and returned on every response")
                        .schema(new StringSchema().example("demo-123")));
        // readAll: ApiError and its nested FieldViolation
        @SuppressWarnings("rawtypes")
        Map<String, Schema> errorSchemas = ModelConverters.getInstance().readAll(ApiError.class);
        errorSchemas.forEach(components::addSchemas);

        return new OpenAPI()
                .info(new Info()
                        .title("AI Claims Platform API")
                        .version(version)
                        .description("Guidewire-style claims handling: FNOL, exposures, reserves, payments, "
                                + "approvals, SIU and AI document assessment.\n\nAPI version **v1** (contract "
                                + version + "). Breaking changes get `/api/v2`; additions bump the minor version."))
                .servers(List.of(new Server().url(serverUrl)))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .pathsToMatch("/api/v1/**")
                .addOperationCustomizer((operation, handlerMethod) -> {
                    operation.addParametersItem(new Parameter().$ref("#/components/parameters/" + CORRELATION_PARAM));
                    ApiResponses responses = operation.getResponses();
                    addError(responses, 400);
                    if (!isPublic(handlerMethod)) {
                        addError(responses, 401);
                        addError(responses, 403);
                    }
                    DocumentedErrors declared = handlerMethod.getMethodAnnotation(DocumentedErrors.class);
                    if (declared != null) {
                        for (int status : declared.value()) {
                            addError(responses, status);
                        }
                    }
                    addError(responses, 500);
                    return operation;
                })
                .build();
    }

    /** Public endpoints are marked with an empty {@code @SecurityRequirements} on the method or class. */
    private static boolean isPublic(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(SecurityRequirements.class)
                || handlerMethod.getBeanType().isAnnotationPresent(SecurityRequirements.class);
    }

    private static void addError(ApiResponses responses, int status) {
        responses.computeIfAbsent(String.valueOf(status), key -> new ApiResponse()
                .description(ERROR_DESCRIPTIONS.getOrDefault(status, "Error"))
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF)))));
    }
}
