package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Horarios (franjas de cita) del medico. El medico gestiona los suyos; el
 * superadmin, los de cualquiera.
 */
@RestController
@RequestMapping("/api/horario")
public class HorarioController {

    public record FranjaDto(Long idAgenda, LocalDateTime inicio, LocalDateTime fin, String estado, String paciente) {
    }

    public record GenerarRequest(Long idMedico,
            LocalDate fecha,
            LocalTime desde,
            LocalTime hasta,
            Integer duracion) {
    }

    private final JdbcTemplate jdbc;

    public HorarioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Medico efectivo: el propio si es MEDICO; si es SUPERADMIN, el que pida. null
     * = falta.
     */
    private static Long medicoDe(AuthUser user, Long idMedicoPedido) {
        return "MEDICO".equals(user.rol()) ? user.idEntidad() : idMedicoPedido;
    }

    private static ResponseEntity<Map<String, String>> sinMedico() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("mensaje", "Falta indicar el medico"));
    }

    @GetMapping
    public ResponseEntity<?> listar(@RequestParam(required = false) Long idMedico,
            @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, idMedico);
        if (medico == null)
            return sinMedico();

        // Las franjas con cita CANCELADA son historial: el horario se vuelve a abrir
        // como una franja nueva.
        List<FranjaDto> franjas = jdbc.query("""
                SELECT a.ID_AGENDA, a.FECHA_HORA_INICIO, a.FECHA_HORA_FIN, est.VALOR AS ESTADO,
                       p.NOMBRE || ' ' || p.APELLIDO AS PACIENTE
                  FROM AGENDAS_MEDICOS a
                  JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = a.ID_ESTADO
                  LEFT JOIN CITAS_MEDICAS c  ON c.ID_AGENDA = a.ID_AGENDA
                  LEFT JOIN PARAMETRICA_TIPOS ce ON ce.ID_PARAMETRO = c.ID_ESTADO
                  LEFT JOIN PACIENTES p      ON p.ID_PACIENTE = c.ID_PACIENTE
                 WHERE a.ID_MEDICO = ?
                   AND a.FECHA_HORA_FIN > LOCALTIMESTAMP
                   AND (c.ID_CITA IS NULL OR ce.VALOR <> 'CANCELADA')
                 ORDER BY a.FECHA_HORA_INICIO
                 FETCH FIRST 300 ROWS ONLY
                """,
                (rs, i) -> new FranjaDto(rs.getLong("ID_AGENDA"),
                        rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(),
                        rs.getTimestamp("FECHA_HORA_FIN").toLocalDateTime(),
                        rs.getString("ESTADO"), rs.getString("PACIENTE")),
                medico);
        return ResponseEntity.ok(franjas);
    }

    @PostMapping
    public ResponseEntity<?> generar(@RequestBody GenerarRequest req, @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, req.idMedico());
        if (medico == null)
            return sinMedico();
        if (req.fecha() == null || req.desde() == null || req.hasta() == null || req.duracion() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("mensaje", "Faltan datos: fecha, hora desde, hora hasta y duracion"));
        }
        Timestamp inicio = Timestamp.valueOf(req.fecha().atTime(req.desde()));
        Timestamp fin = Timestamp.valueOf(req.fecha().atTime(req.hasta()));

        Long creadas = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.GENERAR_FRANJAS(?,?,?,?,?,?)}")) {
                cs.setLong(1, medico);
                cs.setTimestamp(2, inicio);
                cs.setTimestamp(3, fin);
                cs.setInt(4, req.duracion());
                cs.setLong(5, user.idUsuario());
                cs.registerOutParameter(6, Types.NUMERIC);
                cs.execute();
                return cs.getLong(6);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("creadas", creadas));
    }

    @DeleteMapping("/{idAgenda}")
    public ResponseEntity<?> eliminar(@PathVariable Long idAgenda, @AuthenticationPrincipal AuthUser user) {
        // El medico solo borra lo suyo (el paquete lo verifica); el superadmin pasa
        // NULL
        Long soloDe = "MEDICO".equals(user.rol()) ? user.idEntidad() : null;
        if ("MEDICO".equals(user.rol()) && soloDe == null)
            return sinMedico();

        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.ELIMINAR_FRANJA(?,?,?)}")) {
                cs.setLong(1, idAgenda);
                cs.setLong(2, user.idUsuario());
                if (soloDe == null)
                    cs.setNull(3, Types.NUMERIC);
                else
                    cs.setLong(3, soloDe);
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.ok(Map.of("mensaje", "Horario eliminado"));
    }
}
