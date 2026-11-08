package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Perfil del usuario autenticado. Solo telefono y correo son editables. */
@RestController
@RequestMapping("/api/perfil")
public class PerfilController {

    public record ContactoRequest(String telefono, String email) {}

    private final JdbcTemplate jdbc;

    public PerfilController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> ver(@AuthenticationPrincipal AuthUser user) {
        Map<String, Object> perfil = new LinkedHashMap<>();
        perfil.put("username", user.username());
        perfil.put("rol", user.rol());
        perfil.put("editable", false);

        String sql = switch (user.rol()) {
            case "MEDICO" -> """
                    SELECT m.DOCUMENTO, m.NOMBRE, m.APELLIDO, m.TELEFONO, m.EMAIL, esp.VALOR AS ESPECIALIDAD, NULL AS NACIMIENTO
                      FROM MEDICOS m JOIN PARAMETRICA_TIPOS esp ON esp.ID_PARAMETRO = m.ID_ESPECIALIDAD
                     WHERE m.ID_MEDICO = ?""";
            case "PACIENTE" -> """
                    SELECT DOCUMENTO, NOMBRE, APELLIDO, TELEFONO, EMAIL, NULL AS ESPECIALIDAD,
                           TO_CHAR(FECHA_NACIMIENTO, 'YYYY-MM-DD') AS NACIMIENTO
                      FROM PACIENTES WHERE ID_PACIENTE = ?""";
            case "FARMACEUTICO" -> """
                    SELECT DOCUMENTO, NOMBRE, APELLIDO, TELEFONO, EMAIL, NULL AS ESPECIALIDAD, NULL AS NACIMIENTO
                      FROM FARMACEUTICOS WHERE ID_FARMACEUTICO = ?""";
            default -> null;
        };
        if (sql == null || user.idEntidad() == null) return perfil;

        List<Map<String, Object>> filas = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documento", rs.getString("DOCUMENTO"));
            m.put("nombre", rs.getString("NOMBRE"));
            m.put("apellido", rs.getString("APELLIDO"));
            m.put("telefono", rs.getString("TELEFONO"));
            m.put("email", rs.getString("EMAIL"));
            m.put("especialidad", rs.getString("ESPECIALIDAD"));
            m.put("fechaNacimiento", rs.getString("NACIMIENTO"));
            return m;
        }, user.idEntidad());
        if (!filas.isEmpty()) {
            perfil.putAll(filas.get(0));
            perfil.put("editable", true);
        }
        return perfil;
    }

    @PutMapping
    public ResponseEntity<?> actualizar(@RequestBody ContactoRequest req, @AuthenticationPrincipal AuthUser user) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_PERFILES.ACTUALIZAR_CONTACTO(?,?,?,?,?)}")) {
                cs.setString(1, user.rol());
                if (user.idEntidad() == null) cs.setNull(2, Types.NUMERIC); else cs.setLong(2, user.idEntidad());
                cs.setString(3, req.telefono());
                cs.setString(4, req.email());
                cs.setLong(5, user.idUsuario());
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("mensaje", "Datos actualizados"));
    }
}
