package kr.woo.community.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Path uploadRoot;

    public WebConfig(
            @Value("${app.storage.upload-root:./uploads}") String uploadRoot
    ) {
        this.uploadRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers (ResourceHandlerRegistry registry) {
        String uploadLocation = uploadRoot.toUri().toString();

        if (!uploadLocation.endsWith("/")) {
            uploadLocation += "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation);
    }
}
