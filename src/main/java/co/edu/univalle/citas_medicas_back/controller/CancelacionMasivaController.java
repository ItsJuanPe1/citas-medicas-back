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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** HU09: cancelar todas las citas programadas de un medico en una fecha. Ajusta el import de AuthUser. */
@RestController
@RequestMapping("/api/admin/citas/cancelacion-masiva")
public class CancelacionMasivaController {

    public record CancelacionRequest(Long idMedico, LocalDate fecha, String motivo) {}
    public record CitaAfectadaDto(Long idCita, String paciente, String documento, LocalDateTime inicio) {}

    private final JdbcTemplate jdbc;

    public CancelacionMasivaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Vista previa: las citas que se cancelarian (no cambia nada). */
    @GetMapping("/previa")
    public ResponseEntity<?> previa(@RequestParam Long idMedico, @RequestParam LocalDate fecha) {
        List<CitaAfectadaDto> citas = jdbc.query("""
                SELECT c.ID_CITA, p.NOMBRE || ' ' || p.APELLIDO AS PACIENTE, p.DOCUMENTO, c.FECHA_HORA_INICIO
                  FROM TBL_CITAS_MEDICAS c
                  JOIN TBL_AGENDAS_MEDICOS a     ON a.ID_AGENDA = c.ID_AGENDA
                  JOIN TBL_PACIENTES p           ON p.ID_PACIENTE = c.ID_PACIENTE
                  JOIN TBL_PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = c.ID_ESTADO
                 WHERE a.ID_MEDICO = ? AND est.VALOR = 'PROGRAMADA'
                   AND c.FECHA_HORA_INICIO >= ? AND c.FECHA_HORA_INICIO < ?
                 ORDER BY c.FECHA_HORA_INICIO
                """,
                (rs, i) -> new CitaAfectadaDto(rs.getLong("ID_CITA"), rs.getString("PACIENTE"), rs.getString("DOCUMENTO"),
                        rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime()),
                idMedico, Date.valueOf(fecha), Date.valueOf(fecha.plusDays(1)));
        return ResponseEntity.ok(citas);
    }

    @PostMapping
    public ResponseEntity<?> cancelar(@RequestBody CancelacionRequest req, @AuthenticationPrincipal AuthUser user) {
        if (req.idMedico() == null || req.fecha() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("mensaje", "Indica el medico y la fecha"));
        }
        Long canceladas = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_CITAS.PRO_CANCELAR_CITAS_MEDICO(?,?,?,?,?)}")) {
                cs.setLong(1, req.idMedico());
                cs.setDate(2, Date.valueOf(req.fecha()));
                if (req.motivo() == null || req.motivo().isBlank()) cs.setNull(3, Types.VARCHAR); else cs.setString(3, req.motivo());
                cs.setLong(4, user.idUsuario());
                cs.registerOutParameter(5, Types.NUMERIC);
                cs.execute();
                return cs.getLong(5);
            }
        });
        return ResponseEntity.ok(Map.of("canceladas", canceladas));
    }
}
