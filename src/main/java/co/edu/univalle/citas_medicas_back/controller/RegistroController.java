package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Map;

/** Registro publico de pacientes (sin sesion). Toda la validacion vive en PKG_USUARIOS. */
@RestController
@RequestMapping("/api/auth")
public class RegistroController {

    public record RegistroRequest(String documento, String nombre, String apellido, LocalDate fechaNacimiento,
                                  String telefono, String email, String username, String password) {}

    private final JdbcTemplate jdbc;

    public RegistroController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/registro")
    public ResponseEntity<?> registrar(@RequestBody RegistroRequest r) {
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_USUARIOS.REGISTRAR_PACIENTE(?,?,?,?,?,?,?,?,?)}")) {
                cs.setString(1, r.documento());
                cs.setString(2, r.nombre());
                cs.setString(3, r.apellido());
                if (r.fechaNacimiento() == null) cs.setNull(4, Types.DATE); else cs.setDate(4, Date.valueOf(r.fechaNacimiento()));
                cs.setString(5, r.telefono());
                cs.setString(6, r.email());
                cs.setString(7, r.username());
                cs.setString(8, r.password());
                cs.registerOutParameter(9, Types.NUMERIC);
                cs.execute();
                return cs.getLong(9);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idUsuario", id));
    }
}
