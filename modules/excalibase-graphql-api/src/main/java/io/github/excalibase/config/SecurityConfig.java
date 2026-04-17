package io.github.excalibase.config;

import io.github.excalibase.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Autowired(required = false) JwtAuthFilter jwtAuthFilter) throws Exception {
        // CSRF disabled — stateless JWT API, no session cookies, Bearer-token auth only
        http.csrf(csrf -> csrf.disable()) // NOSONAR
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        if (jwtAuthFilter != null) {
            http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
