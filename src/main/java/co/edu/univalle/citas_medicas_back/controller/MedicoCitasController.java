package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Agenda del medico autenticado. Base para HU03 (registrar consulta). */
@RestController
@RequestMapping("/api/medico")
public class MedicoCitasController {

    public record CitaMedicoDto(Long idCita, String paciente, String documento,
            LocalDateTime inicio, String estado) {
    }

    private final JdbcTemplate jdbc;

    public MedicoCitasController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * historial=false: citas programadas de hoy en adelante. historial=true: todas,
     * las mas recientes primero.
     */
    @GetMapping("/citas")
    public ResponseEntity<?> citas(@RequestParam(defaultValue = "false") boolean historial,
            @AuthenticationPrincipal AuthUser user) {
        if (user.idEntidad() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("mensaje", "El usuario no tiene un medico asociado"));
        }
        String sql = """
                SELECT c.ID_CITA, p.NOMBRE || ' ' || p.APELLIDO AS PACIENTE, p.DOCUMENTO,
                       a.FECHA_HORA_INICIO, est.VALOR AS ESTADO
                  FROM CITAS_MEDICAS c
                  JOIN AGENDAS_MEDICOS a     ON a.ID_AGENDA = c.ID_AGENDA
                  JOIN PACIENTES p           ON p.ID_PACIENTE = c.ID_PACIENTE
                  JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = c.ID_ESTADO
                 WHERE a.ID_MEDICO = ?
                """
                + (historial
                        ? " ORDER BY a.FECHA_HORA_INICIO DESC "
                        : " AND est.VALOR = 'PROGRAMADA' AND a.FECHA_HORA_INICIO >= TRUNC(SYSDATE) ORDER BY a.FECHA_HORA_INICIO ")
                + " FETCH FIRST 200 ROWS ONLY";

        List<CitaMedicoDto> citas = jdbc.query(sql,
                (rs, i) -> new CitaMedicoDto(rs.getLong("ID_CITA"), rs.getString("PACIENTE"), rs.getString("DOCUMENTO"),
                        rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(), rs.getString("ESTADO")),
                user.idEntidad());
        return ResponseEntity.ok(citas);
    }
}
