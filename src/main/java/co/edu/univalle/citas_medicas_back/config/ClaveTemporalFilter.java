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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mientras la clave sea temporal (TBL_USUARIOS.DEBE_CAMBIAR_CLAVE = 'S') el usuario solo puede usar /api/cuenta/**
 * (ver el estado y cambiar la clave).
 * Cachea el estado por usuario con TTL corto e invalidacion explicita al cambiar/restablecer clave,
 * evitando una consulta (y un PRO_ESTABLECER_CONTEXTO) extra en cada peticion autenticada.
 */
@Component
public class ClaveTemporalFilter extends OncePerRequestFilter {

    private static final long TTL_MS = 120_000L; // 2 minutos

    private record CacheEntry(boolean temporal, long expiraEn) {}

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public ClaveTemporalFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Invalida el cache cuando el usuario cambia su clave o un admin la restablece. */
    public void invalidar(Long idUsuario) {
        if (idUsuario != null) {
            cache.remove(idUsuario);
        }
    }

    private boolean debeCambiarClave(long idUsuario) {
        long ahora = System.currentTimeMillis();
        CacheEntry entry = cache.get(idUsuario);
        if (entry != null && ahora < entry.expiraEn()) {
            return entry.temporal();
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM TBL_USUARIOS WHERE ID_USUARIO = ? AND DEBE_CAMBIAR_CLAVE = 'S'",
                Integer.class, idUsuario);
        boolean temporal = count != null && count > 0;
        cache.put(idUsuario, new CacheEntry(temporal, ahora + TTL_MS));
        return temporal;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = req.getRequestURI();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user
                && path.startsWith("/api/") && !path.startsWith("/api/cuenta")) {
            if (debeCambiarClave(user.idUsuario())) {
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
