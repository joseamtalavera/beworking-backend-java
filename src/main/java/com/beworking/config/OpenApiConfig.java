package com.beworking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for OpenAPI/Swagger documentation.
 * 
 * <p>This configuration sets up the API documentation that can be accessed
 * at {@code /swagger-ui.html} when the application is running.
 * 
 * @author BeWorking Team
 * @since 1.0
 */
@Configuration
public class OpenApiConfig {

    /**
     * Configures the OpenAPI specification for the BeWorking API.
     * 
     * @return Configured OpenAPI instance
     */
    @Bean
    public OpenAPI beworkingOpenAPI() {
        Server devServer = new Server();
        devServer.setUrl("http://localhost:8080");
        devServer.setDescription("Development Server");

        Server prodServer = new Server();
        prodServer.setUrl("https://api.beworking.com");
        prodServer.setDescription("Production Server");

        Contact contact = new Contact();
        contact.setEmail("support@beworking.com");
        contact.setName("BeWorking Support");

        License license = new License();
        license.setName("Proprietary");
        license.setUrl("https://beworking.com/license");

        Info info = new Info()
                .title("BeWorking API")
                .version("1.0.0")
                .contact(contact)
                .description("""
                    RESTful API for the BeWorking workspace management platform.
                    
                    ## Authentication
                    Most endpoints require authentication via JWT tokens. Include the token in the Authorization header:
                    ```
                    Authorization: Bearer <your-jwt-token>
                    ```
                    
                    ## Multi-Tenancy
                    All data is scoped to tenants. The tenant context is derived from the authenticated user's JWT token.
                    
                    ## Rate Limiting
                    API requests are rate-limited:
                    - Authenticated: 100 requests/minute
                    - Unauthenticated: 10 requests/minute
                    """)
                .license(license);

        return new OpenAPI()
                .info(info)
                .servers(List.of(devServer, prodServer));
    }
}


