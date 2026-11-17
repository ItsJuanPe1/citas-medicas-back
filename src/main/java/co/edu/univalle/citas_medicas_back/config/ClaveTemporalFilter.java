package co.edu.univalle.citas_medicas_back.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Mientras la clave sea temporal (TBL_USUARIOS.DEBE_CAMBIAR_CLAVE = 'S') el usuario solo puede usar /api/cuenta/**
 * (ver el estado y cambiar la clave). Es un @Component: Spring Boot lo registra solo, no hay que tocar SecurityConfig.
 */
@Component
public class ClaveTemporalFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    public ClaveTemporalFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = req.getRequestURI();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user
                && path.startsWith("/api/") && !path.startsWith("/api/cuenta")) {
            Integer temporal = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM TBL_USUARIOS WHERE ID_USUARIO = ? AND DEBE_CAMBIAR_CLAVE = 'S'",
                    Integer.class, user.idUsuario());
            if (temporal != null && temporal > 0) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json;charset=UTF-8");
                res.getOutputStream().write(
                        "{\"mensaje\":\"Debes cambiar tu contraseña temporal antes de continuar\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
