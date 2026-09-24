package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfig {
    @Bean SecretKeySpec jwtKey(@Value("${chat.jwt-secret}") String secret, Environment env) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 && !env.matchesProfiles("prod")) {
            bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        }
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKeySpec key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("chatapp-secure"));
        return decoder;
    }
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/api/auth/register", "/api/auth/login", "/ws", "/error").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")))
            .build();
    }
}
