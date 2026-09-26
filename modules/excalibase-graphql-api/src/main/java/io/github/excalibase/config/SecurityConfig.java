package io.github.excalibase.config;

import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.KnownProjectFilter;
import io.github.excalibase.security.KnownProjects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           CorsConfigurationSource corsConfigurationSource,
                                           KnownProjects knownProjects,
                                           @Autowired(required = false) JwtAuthFilter jwtAuthFilter) throws Exception {
        // CSRF disabled — stateless JWT API, no session cookies, Bearer-token auth only.
        // CORS runs here, ahead of auth, so a preflight never needs a token and the
        // per-project origin check covers every servlet path (incl. WS upgrades) of a
        // known project.
        http.csrf(csrf -> csrf.disable()) // NOSONAR
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        // An unknown project is refused first: it needs no CORS answer and no token
        // check, and neither lookup should reach provisioning on its behalf.
        http.addFilterBefore(new KnownProjectFilter(knownProjects), CorsFilter.class);
        if (jwtAuthFilter != null) {
            http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
