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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gestion de cuentas por el SUPERADMIN: listar, crear personal, activar/desactivar, restablecer clave. */
@RestController
@RequestMapping("/api/admin")
public class UsuarioController {

    public record UsuarioDto(Long idUsuario, String username, String rol, String nombre, String estado) {}
    public record EspecialidadDto(Long id, String nombre) {}
    public record CrearRequest(String rol, String documento, String nombre, String apellido, Long idEspecialidad,
                               String telefono, String email, String username, String password) {}
    public record EstadoRequest(String estado) {}
    public record ClaveRequest(String password) {}

    private final JdbcTemplate jdbc;

    public UsuarioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/usuarios")
    public List<UsuarioDto> listar(@RequestParam(required = false) String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                  SELECT u.ID_USUARIO, u.USERNAME, r.NOMBRE AS ROL, u.ESTADO,
                         CASE r.NOMBRE
                           WHEN 'MEDICO'       THEN (SELECT m.NOMBRE || ' ' || m.APELLIDO FROM TBL_MEDICOS m WHERE m.ID_MEDICO = u.ID_ENTIDAD)
                           WHEN 'PACIENTE'     THEN (SELECT p.NOMBRE || ' ' || p.APELLIDO FROM TBL_PACIENTES p WHERE p.ID_PACIENTE = u.ID_ENTIDAD)
                           WHEN 'FARMACEUTICO' THEN (SELECT f.NOMBRE || ' ' || f.APELLIDO FROM TBL_FARMACEUTICOS f WHERE f.ID_FARMACEUTICO = u.ID_ENTIDAD)
                         END AS NOMBRE
                    FROM TBL_USUARIOS u JOIN TBL_ROLES r ON r.ID_ROL = u.ID_ROL
                ) WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (LOWER(USERNAME) LIKE ? OR LOWER(NOMBRE) LIKE ? OR ROL LIKE ?) ");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add("%" + q.trim().toUpperCase() + "%");
        }
        sql.append(" ORDER BY ID_USUARIO DESC FETCH FIRST 100 ROWS ONLY");
        return jdbc.query(sql.toString(), (rs, i) -> new UsuarioDto(rs.getLong("ID_USUARIO"), rs.getString("USERNAME"),
                rs.getString("ROL"), rs.getString("NOMBRE"), rs.getString("ESTADO")), args.toArray());
    }

    @GetMapping("/especialidades")
    public List<EspecialidadDto> especialidades() {
        return jdbc.query("SELECT ID_PARAMETRO, VALOR FROM TBL_PARAMETRICA_TIPOS WHERE NOMBRE = 'ESPECIALIDAD' ORDER BY VALOR",
                (rs, i) -> new EspecialidadDto(rs.getLong(1), rs.getString(2)));
    }

    @PostMapping("/usuarios")
    public ResponseEntity<?> crear(@RequestBody CrearRequest r, @AuthenticationPrincipal AuthUser user) {
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.PRO_CREAR_PERSONAL(?,?,?,?,?,?,?,?,?,?,?)}")) {
                cs.setString(1, r.rol());
                cs.setString(2, r.documento());
                cs.setString(3, r.nombre());
                cs.setString(4, r.apellido());
                if (r.idEspecialidad() == null) cs.setNull(5, Types.NUMERIC); else cs.setLong(5, r.idEspecialidad());
                cs.setString(6, r.telefono());
                cs.setString(7, r.email());
                cs.setString(8, r.username());
                cs.setString(9, r.password());
                cs.setLong(10, user.idUsuario());
                cs.registerOutParameter(11, Types.NUMERIC);
                cs.execute();
                return cs.getLong(11);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idUsuario", id));
    }

    @PutMapping("/usuarios/{id}/estado")
    public ResponseEntity<?> estado(@PathVariable Long id, @RequestBody EstadoRequest r, @AuthenticationPrincipal AuthUser user) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.PRO_CAMBIAR_ESTADO(?,?,?)}")) {
                cs.setLong(1, id);
                cs.setString(2, r.estado());
                cs.setLong(3, user.idUsuario());
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.ok(Map.of("mensaje", "Estado actualizado"));
    }

    @PutMapping("/usuarios/{id}/clave")
    public ResponseEntity<?> clave(@PathVariable Long id, @RequestBody ClaveRequest r, @AuthenticationPrincipal AuthUser user) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.PRO_RESTABLECER_CLAVE(?,?,?)}")) {
                cs.setLong(1, id);
                cs.setString(2, r.password());
                cs.setLong(3, user.idUsuario());
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.ok(Map.of("mensaje", "Contrasena restablecida"));
    }
}
