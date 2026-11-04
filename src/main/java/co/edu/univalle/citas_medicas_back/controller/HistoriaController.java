package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.CallableStatement;
import java.sql.Types;
import java.util.Map;

/** HU03: registrar la consulta (historia clinica) de una cita. */
@RestController
@RequestMapping("/api/historias")
public class HistoriaController {

    public record ConsultaRequest(Long idCita, String diagnostico, String notas) {
    }

    private final JdbcTemplate jdbc;

    public HistoriaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody ConsultaRequest req, @AuthenticationPrincipal AuthUser user) {
        if (req.idCita() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("mensaje", "Falta la cita"));
        }
        // El medico solo registra consultas de sus propias citas; el superadmin,
        // cualquiera.
        Long idMedico = "MEDICO".equals(user.rol()) ? user.idEntidad() : null;
        if ("MEDICO".equals(user.rol()) && idMedico == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("mensaje", "El usuario no tiene un medico asociado"));
        }

        Long idHistoria = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con
                    .prepareCall("{call PKG_HISTORIAS_CLINICAS.REGISTRAR_CONSULTA(?,?,?,?,?,?)}")) {
                cs.setLong(1, req.idCita());
                cs.setString(2, req.diagnostico());
                cs.setString(3, req.notas());
                cs.setLong(4, user.idUsuario());
                cs.registerOutParameter(5, Types.NUMERIC);
                if (idMedico == null)
                    cs.setNull(6, Types.NUMERIC);
                else
                    cs.setLong(6, idMedico);
                cs.execute();
                return cs.getLong(5);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idHistoria", idHistoria));
    }
}
