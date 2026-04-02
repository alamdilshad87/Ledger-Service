package dev.ledger.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}. Both are public;
 * calling the API from the UI needs an API key (Authorize button).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger Service API")
                        .version("v1")
                        .description("""
                                Double-entry wallet and payments ledger. All amounts are integers in paise \
                                (₹1 = 100 paise).

                                - Authenticate with `Authorization: Bearer <api key>`; `/v1/admin/**` needs an ADMIN key.
                                - Every money-moving POST needs an `Idempotency-Key`. Retrying with the same key \
                                replays the original response; a different body with the same key is 422.
                                - Errors are `{ "code", "message", "traceId" }`; the trace id is also in `X-Request-Id`.
                                - Each API key has a rate limit; over it you get 429 with `Retry-After`."""))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("API key, e.g. lk_3f9a…")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }

    /** Documents the Idempotency-Key header on exactly the endpoints the idempotency filter protects. */
    @Bean
    OperationCustomizer idempotencyKeyHeader() {
        return (operation, handlerMethod) -> {
            var mapping = handlerMethod.getMethodAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            boolean movesMoney = mapping != null && handlerMethod.getBeanType().getPackageName().matches(
                    "dev\\.ledger\\.(topup|transfer|payment)");
            if (movesMoney) {
                // The controllers already take the header; make sure it's described properly.
                if (operation.getParameters() != null) {
                    operation.getParameters().removeIf(p -> IdempotencyKeys.HEADER.equalsIgnoreCase(p.getName()));
                }
                operation.addParametersItem(new HeaderParameter()
                        .name(IdempotencyKeys.HEADER)
                        .required(true)
                        .description("Unique per operation (a UUID works). Retries must reuse it. Max 255 characters.")
                        .schema(new StringSchema().maxLength(255)));
            }
            return operation;
        };
    }
}
