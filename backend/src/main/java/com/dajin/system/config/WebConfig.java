package com.dajin.system.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);
    private final AuthInterceptor interceptor;
    private final List<String> allowedOrigins;
    public WebConfig(AuthInterceptor interceptor, @Value("${app.cors-origins}") String origins) {
        this.interceptor=interceptor;
        this.allowedOrigins = java.util.Arrays.stream(origins.split(","))
                .map(String::trim).filter(v -> !v.isBlank()).toList();
        log.info("Configured CORS origins: {}", this.allowedOrigins);
    }
    @Override public void addInterceptors(InterceptorRegistry r) { r.addInterceptor(interceptor).addPathPatterns("/api/**"); }
    @Bean public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry r) {
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path root = java.nio.file.Files.isDirectory(cwd.resolve("backend")) ? cwd.resolve("backend/uploads") : cwd.resolve("uploads");
        r.addResourceHandler("/uploads/**").addResourceLocations("file:" + root.toAbsolutePath() + "/");
    }
}
