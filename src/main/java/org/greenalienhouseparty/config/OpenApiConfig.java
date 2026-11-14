package org.greenalienhouseparty.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAPI document configuration for the published Swagger specification.
 */
@Configuration
public class OpenApiConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfig.class);

    /**
     * Defines the OpenAPI document metadata.
     *
     * @return the configured OpenAPI document
     */
    @Bean
    public OpenAPI spaceAppsOpenApi() {
        log.trace("Entering spaceAppsOpenApi()");
        log.debug("Creating OpenAPI metadata for the backend API");
        try {
            return new OpenAPI().info(new Info()
                    .title("Space Apps 2026 Backend API")
                    .description("Backend API for the Space Apps 2026 crop-rotation decision support tool. "
                            + "Serves the frontend and proxies the Python data backend.")
                    .version("0.1.0"));
        } finally {
            log.trace("Exiting spaceAppsOpenApi()");
        }
    }
}
