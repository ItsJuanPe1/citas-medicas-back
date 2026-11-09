package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Map;

/** La recepcionista registra un paciente que llega sin cuenta (solo ficha, sin usuario). */
@RestController
@RequestMapping("/api/pacientes")
public class PacienteRegistroController {

    public record NuevoPacienteRequest(String documento, String nombre, String apellido,
                                       LocalDate fechaNacimiento, String telefono, String email) {}

    private final JdbcTemplate jdbc;

    public PacienteRegistroController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody NuevoPacienteRequest r, @AuthenticationPrincipal AuthUser user) {
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.REGISTRAR_PACIENTE_RECEPCION(?,?,?,?,?,?,?,?)}")) {
                cs.setString(1, r.documento());
                cs.setString(2, r.nombre());
                cs.setString(3, r.apellido());
                if (r.fechaNacimiento() == null) cs.setNull(4, Types.DATE); else cs.setDate(4, Date.valueOf(r.fechaNacimiento()));
                cs.setString(5, r.telefono());
                cs.setString(6, r.email());
                cs.setLong(7, user.idUsuario());
                cs.registerOutParameter(8, Types.NUMERIC);
                cs.execute();
                return cs.getLong(8);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", id, "documento", r.documento().trim(), "nombre", r.nombre().trim(), "apellido", r.apellido().trim()));
    }
}
