package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String rol, Long idEntidad) {
    }

    private final JdbcTemplate jdbc;
    private final JwtService jwt;

    public AuthController(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            return error(HttpStatus.BAD_REQUEST, "Usuario y contraseña son obligatorios");
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.ID_USUARIO, u.USERNAME, u.PASSWORD, u.ID_ENTIDAD, u.ESTADO, r.NOMBRE AS ROL
                FROM USUARIOS u JOIN ROLES r ON r.ID_ROL = u.ID_ROL
                WHERE u.USERNAME = ?
                """, req.username());

        if (rows.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        Map<String, Object> u = rows.get(0);

        String hashIngresado = sha256Hex(req.password());
        String hashGuardado = ((String) u.get("PASSWORD")).toLowerCase();
        if (!MessageDigest.isEqual(hashIngresado.getBytes(StandardCharsets.UTF_8),
                hashGuardado.getBytes(StandardCharsets.UTF_8))) {
            return error(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!"ACTIVO".equals(u.get("ESTADO"))) {
            return error(HttpStatus.FORBIDDEN, "Usuario inactivo");
        }

        long idUsuario = ((Number) u.get("ID_USUARIO")).longValue();
        Long idEntidad = u.get("ID_ENTIDAD") == null ? null : ((Number) u.get("ID_ENTIDAD")).longValue();
        String rol = (String) u.get("ROL");
        String username = (String) u.get("USERNAME");

        String token = jwt.generar(idUsuario, username, rol, idEntidad);
        return ResponseEntity.ok(new LoginResponse(token, username, rol, idEntidad));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("mensaje", msg));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}