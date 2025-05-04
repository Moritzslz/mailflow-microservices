package de.flowsuite.security.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class RestClientConfig {

    private final String baseUrl;
    private final JwtRestClientInterceptor jwtRestClientInterceptor;

    RestClientConfig(
            @Value("${mailflow.api.base-url}") String baseUrl,
            JwtRestClientInterceptor jwtRestClientInterceptor) {
        this.baseUrl = baseUrl;
        this.jwtRestClientInterceptor = jwtRestClientInterceptor;
    }

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(jwtRestClientInterceptor)
                .build();
    }
}
