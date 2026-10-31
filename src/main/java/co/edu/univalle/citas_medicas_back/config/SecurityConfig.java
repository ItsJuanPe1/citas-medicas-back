package co.edu.univalle.citas_medicas_back.config; // usa el package donde ya lo tienes

import co.edu.univalle.citas_medicas_back.config.JwtAuthFilter;
import co.edu.univalle.citas_medicas_back.config.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Publico
                        .requestMatchers("/api/health", "/api/auth/login", "/error").permitAll()

                        // === Reglas por modulo. Cada HU agrega sus lineas aqui. ===
                        // Citas (HU01, HU02): SUPERADMIN puede todo
                        .requestMatchers("/api/agendas/**", "/api/citas", "/api/citas/**")
                        .hasAnyRole("PACIENTE", "RECEPCIONISTA", "SUPERADMIN")
                        .requestMatchers("/api/pacientes").hasAnyRole("RECEPCIONISTA", "SUPERADMIN")
                        // Lista de medicos: la usan quienes reservan o consultan citas
                        .requestMatchers("/api/medicos")
                        .hasAnyRole("PACIENTE", "RECEPCIONISTA", "SUPERADMIN", "GERENTE")
                        // Agenda del medico (base de HU03)
                        .requestMatchers("/api/medico/**").hasRole("MEDICO")
                        // Administracion: consultas de solo lectura
                        .requestMatchers("/api/admin/citas").hasAnyRole("SUPERADMIN", "GERENTE")
                        .requestMatchers("/api/admin/auditoria").hasRole("SUPERADMIN")
                        // Historias clinicas (HU03, HU04): pendiente
                        // Medicamentos y farmacia (HU05, HU06): pendiente
                        // ==========================================================

                        // Todo lo demas: cualquier usuario con sesion
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:5174"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }
}
