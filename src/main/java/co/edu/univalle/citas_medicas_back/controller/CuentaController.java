package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import co.edu.univalle.citas_medicas_back.config.ClaveTemporalFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.util.Map;

/** Cuenta del usuario autenticado: si debe cambiar su clave temporal y el cambio de clave. */
@RestController
@RequestMapping("/api/cuenta")
public class CuentaController {

    public record CambioClaveRequest(String actual, String nueva) {}

    private final JdbcTemplate jdbc;
    private final ClaveTemporalFilter claveTemporalFilter;

    public CuentaController(JdbcTemplate jdbc, ClaveTemporalFilter claveTemporalFilter) {
        this.jdbc = jdbc;
        this.claveTemporalFilter = claveTemporalFilter;
    }

    @GetMapping
    public Map<String, Boolean> estado(@AuthenticationPrincipal AuthUser user) {
        String flag = jdbc.queryForObject("SELECT DEBE_CAMBIAR_CLAVE FROM TBL_USUARIOS WHERE ID_USUARIO = ?",
                String.class, user.idUsuario());
        return Map.of("debeCambiarClave", "S".equals(flag));
    }

    @PutMapping("/clave")
    public ResponseEntity<?> cambiarClave(@RequestBody CambioClaveRequest req, @AuthenticationPrincipal AuthUser user) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.PRO_CAMBIAR_CLAVE(?,?,?)}")) {
                cs.setLong(1, user.idUsuario());
                cs.setString(2, req.actual());
                cs.setString(3, req.nueva());
                cs.execute();
                return null;
            }
        });
        claveTemporalFilter.invalidar(user.idUsuario());
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
