package de.flowsuite.security.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class RestClientConfig {

    private final String apiBaseUrl;
    private final String llmServiceBaseUrl;
    private final JwtRestClientInterceptor jwtRestClientInterceptor;

    RestClientConfig(
            @Value("${mailflow.api.base-url}") String apiBaseUrl,
            @Value("${mailflow.llm-service.base-url}") String llmServiceBaseUrl,
            JwtRestClientInterceptor jwtRestClientInterceptor) {
        this.apiBaseUrl = apiBaseUrl;
        this.llmServiceBaseUrl = llmServiceBaseUrl;
        this.jwtRestClientInterceptor = jwtRestClientInterceptor;
    }

    @Bean
    public RestClient apiRestClient() {
        return RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestInterceptor(jwtRestClientInterceptor)
                .build();
    }

    @Bean
    public RestClient llmServiceRestClient() {
        return RestClient.builder()
                .baseUrl(llmServiceBaseUrl)
                .requestInterceptor(jwtRestClientInterceptor)
                .build();
    }
}
