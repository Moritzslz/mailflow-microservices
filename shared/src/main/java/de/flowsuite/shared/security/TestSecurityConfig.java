package de.flowsuite.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

@Configuration
@Profile("test")
class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        RSAPublicKey publicKey = (RSAPublicKey) generateDummyKeyPair().getPublic();
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    private KeyPair generateDummyKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate dummy RSA key pair for tests", e);
        }
    }
}
