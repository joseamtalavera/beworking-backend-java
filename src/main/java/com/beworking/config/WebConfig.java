package com.beworking.config; // This is the package declaration

import org.springframework.context.annotation.Bean; // Bean annotation is for defining beans. Beans are objects that are instantiated, assembled, and managed by a Spring IoC container. IoC is Inversion of Control. It serves as a bridge between the Spring IoC container and the application. The Spring IoC container is responsible for instantiating, configuring, and assembling the beans. The @Bean annotation indicates that a method produces a bean to be managed by the Spring container.
import org.springframework.context.annotation.Configuration; // Configuration annotation indicates that the class has `@Bean` definition methods. These methods return an object that should be registered as a bean in the Spring application context. The `@Configuration` annotation is a specialization of the `@Component` annotation, allowing for more fine-grained control over the configuration of the Spring application context.
import org.springframework.web.servlet.config.annotation.CorsRegistry; // CorsRegistry is used to configure CORS (Cross-Origin Resource Sharing) settings for the application. CORS is a security feature implemented by web browsers to prevent malicious websites from making requests to a different domain than the one that served the web page. It allows you to specify which origins are allowed to access resources on your server.
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer; // WebMvcConfigurer is an interface that provides callback methods to customize the Java-based configuration for Spring MVC. It allows you to configure various aspects of the Spring MVC framework, such as view resolution, message converters, CORS support, and more. By implementing this interface, you can customize the default behavior of Spring MVC without having to extend or modify the default configuration classes.

@Configuration // Configuration annotation indicates that the class has `@Bean` definition methods. These methods return an object that should be registered as a bean in the Spring application context.
public class WebConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:3020") // Allow requests from this origin
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Allow these HTTP methods
                        .allowedHeaders("*");
                }
            };
    }
    
}