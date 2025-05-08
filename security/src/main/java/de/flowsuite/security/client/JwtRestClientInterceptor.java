package de.flowsuite.security.client;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.SignedJWT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

@Component
class JwtRestClientInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(JwtRestClientInterceptor.class);
    private static final String AUTHENTICATION_URI = "/auth/token/clients";
    private final String baseUrl;
    private final String clientName;
    private final String clientSecret;
    private String jwt;

    JwtRestClientInterceptor(
            @Value("${mailflow.api.base-url}") String baseUrl,
            @Value("${client.name}") String clientName,
            @Value("${client.secret}") String clientSecret) {
        this.baseUrl = baseUrl;
        this.clientName = clientName;
        this.clientSecret = clientSecret;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (jwt == null || isTokenExpired(jwt)) {
            jwt = fetchAccessToken();
        }

        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);

        ClientHttpResponse response = execution.execute(request, body);

        if (response.getStatusCode().value() == 401) {
            LOG.debug("API returned 401. Fetching new JWT");
            jwt = fetchAccessToken();
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
            return execution.execute(request, body);
        }

        return response;
    }

    private boolean isTokenExpired(String jwt) {
        LOG.debug("Checking if JWT is expired");
        try {
            JWT parsedJwt = SignedJWT.parse(jwt);
            Date expirationTime = parsedJwt.getJWTClaimsSet().getExpirationTime();
            return expirationTime == null || expirationTime.toInstant().isBefore(Instant.now());
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private String fetchAccessToken() {
        LOG.debug("Fetching JWT from API");
        ClientTokenResponse tokenResponse =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .build()
                        .post()
                        .uri(AUTHENTICATION_URI)
                        .body(new ClientLoginRequest(clientName, clientSecret))
                        .retrieve()
                        .body(ClientTokenResponse.class);

        if (tokenResponse == null || tokenResponse.accessToken() == null) {
            throw new RuntimeException("Failed to retrieve JWT");
        }

        return tokenResponse.accessToken;
    }

    record ClientLoginRequest(String clientName, String clientSecret) {}

    record ClientTokenResponse(String accessToken) {}
}
