package io.github.excalibase.config;

import io.github.excalibase.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           CorsConfigurationSource corsConfigurationSource,
                                           @Autowired(required = false) JwtAuthFilter jwtAuthFilter) throws Exception {
        // CSRF disabled — stateless JWT API, no session cookies, Bearer-token auth only.
        // CORS runs here, ahead of auth, so a preflight never needs a token and the
        // per-project origin check covers every servlet path (incl. WS upgrades).
        http.csrf(csrf -> csrf.disable()) // NOSONAR
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        if (jwtAuthFilter != null) {
            http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
