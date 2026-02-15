package com.beworking.auth;

import com.beworking.auth.RateLimitingFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Spring Security configuration for the BeWorking backend.
 *
 * <p>Defines CORS, stateless auth, public routes, and request filters.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;

    @Value("${app.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Autowired
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitingFilter rateLimitingFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitingFilter = rateLimitingFilter;
    }

    /**
     * Configure endpoints that bypass Spring Security filters entirely.
     *
     * @return the web security customizer.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/api/health", "/uploads/**");
    }

    /**
     * Password encoder for credential hashing.
     *
     * @return a BCrypt password encoder.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS configuration for API routes.
     *
     * @return the CORS configuration source bound to /api/**.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Use setAllowedOrigins for exact matches when allowCredentials is true
        // setAllowedOriginPatterns would work but setAllowedOrigins is more explicit for exact URLs
        List<String> allowedOrigins = parseAllowedOrigins();
        if (!allowedOrigins.isEmpty()) {
            configuration.setAllowedOrigins(allowedOrigins);
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * Main Spring Security filter chain configuration.
     *
     * @param http the security builder.
     * @return the configured security filter chain.
     * @throws Exception if the configuration fails.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                .requestMatchers("/").permitAll()
                .requestMatchers("/api/health","/api/auth/login", "/api/auth/admin/login", "/api/auth/register", "/api/auth/confirm", "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/refresh", "/api/leads", "/api/mailroom/**", "/api/invoices/*/pdf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/uploads").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/contact-profiles/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/contact-profiles").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/contact-profiles/**").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/contact-profiles/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/public/**").permitAll()
                .requestMatchers("/dashboard/admin/**").hasRole("ADMIN")
                .requestMatchers("/dashboard/user/**").hasRole("USER")
                .anyRequest().authenticated()
            )
            .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers
                .contentTypeOptions(withDefaults())
                .xssProtection(withDefaults())
                .frameOptions(withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true)
                )
            );
        return http.build();
    }

    /**
     * Authentication manager wiring for Spring Security.
     *
     * @param authenticationConfiguration the auth configuration.
     * @return the authentication manager.
     * @throws Exception if resolution fails.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }


    private List<String> parseAllowedOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();
    }
}
