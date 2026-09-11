package com.vpn.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Carved out ahead of the /api/v1/auth/** permitAll wildcard below:
                        // this is the one endpoint under that prefix that needs a real
                        // session (it mints a web-handoff code for the *calling* user).
                        // Matched as an exact path, so it doesn't also swallow
                        // /api/v1/auth/web-handoff/exchange (which must stay public: the
                        // whole point of that endpoint is the caller has no session yet).
                        .requestMatchers("/api/v1/auth/web-handoff").authenticated()
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/vite.svg",
                                "/favicon.ico",
                                "/api/v1/auth/**",
                                "/api/v1/health",
                                "/api/v1/user/tariffs",
                                "/api/v1/subscription/export/**",
                                "/api/v1/client/**",
                                "/api/v1/telegram/**",
                                "/actuator/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
