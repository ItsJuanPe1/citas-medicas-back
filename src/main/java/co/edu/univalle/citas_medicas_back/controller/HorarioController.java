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

/** Agendas (rangos de atencion) del medico. El medico gestiona las suyas; el superadmin, las de cualquiera. */
@RestController
@RequestMapping("/api/horario")
public class HorarioController {

    public record AgendaDto(Long idAgenda, LocalDateTime inicio, LocalDateTime fin, String estado,
                            Integer citas, Integer citasTotales) {}

    public record BloqueDto(String dia, String desde, String hasta) {}

    public record SemanalRequest(Long idMedico, List<BloqueDto> bloques) {}

    public record CrearRequest(Long idMedico, LocalDate fecha, LocalTime desde, LocalTime hasta) {}

    private final JdbcTemplate jdbc;

    public HorarioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Medico efectivo: el propio si es MEDICO; si es SUPERADMIN, el que pida. null = falta. */
    private static Long medicoDe(AuthUser user, Long idMedicoPedido) {
        return "MEDICO".equals(user.rol()) ? user.idEntidad() : idMedicoPedido;
    }

    private static ResponseEntity<Map<String, String>> sinMedico() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("mensaje", "Falta indicar el medico"));
    }

    /** Agendas proximas: disponibles o con citas vigentes. citas = vigentes; citasTotales = incluye canceladas. */
    @GetMapping
    public ResponseEntity<?> listar(@RequestParam(required = false) Long idMedico, @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, idMedico);
        if (medico == null) return sinMedico();

        List<AgendaDto> agendas = jdbc.query("""
                SELECT a.ID_AGENDA, a.FECHA_HORA_INICIO, a.FECHA_HORA_FIN, est.VALOR AS ESTADO,
                       COUNT(CASE WHEN ce.VALOR <> 'CANCELADA' THEN 1 END) AS CITAS,
                       COUNT(c.ID_CITA) AS CITAS_TOTALES
                  FROM AGENDAS_MEDICOS a
                  JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = a.ID_ESTADO
                  LEFT JOIN CITAS_MEDICAS c        ON c.ID_AGENDA = a.ID_AGENDA
                  LEFT JOIN PARAMETRICA_TIPOS ce   ON ce.ID_PARAMETRO = c.ID_ESTADO
                 WHERE a.ID_MEDICO = ?
                   AND a.FECHA_HORA_FIN > LOCALTIMESTAMP
                 GROUP BY a.ID_AGENDA, a.FECHA_HORA_INICIO, a.FECHA_HORA_FIN, est.VALOR
                HAVING est.VALOR = 'DISPONIBLE' OR COUNT(CASE WHEN ce.VALOR <> 'CANCELADA' THEN 1 END) > 0
                 ORDER BY a.FECHA_HORA_INICIO
                 FETCH FIRST 1000 ROWS ONLY
                """,
                (rs, i) -> new AgendaDto(rs.getLong("ID_AGENDA"),
                        rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(),
                        rs.getTimestamp("FECHA_HORA_FIN").toLocalDateTime(),
                        rs.getString("ESTADO"), rs.getInt("CITAS"), rs.getInt("CITAS_TOTALES")),
                medico);
        return ResponseEntity.ok(agendas);
    }

    /** Horario semanal del medico: lo que se repite cada semana, deducido de sus agendas futuras. */
    @GetMapping("/semanal")
    public ResponseEntity<?> semanal(@RequestParam(required = false) Long idMedico, @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, idMedico);
        if (medico == null) return sinMedico();
        // El horario semanal se deduce de las agendas futuras: mismo dia y mismas horas en 2 o mas fechas
        List<BloqueDto> bloques = jdbc.query("""
                SELECT DIA_SEMANA,
                       TO_CHAR(FECHA_HORA_INICIO, 'HH24:MI') AS DESDE,
                       TO_CHAR(FECHA_HORA_FIN, 'HH24:MI')    AS HASTA
                  FROM AGENDAS_MEDICOS
                 WHERE ID_MEDICO = ? AND FECHA_HORA_FIN > LOCALTIMESTAMP
                 GROUP BY DIA_SEMANA, TO_CHAR(FECHA_HORA_INICIO, 'HH24:MI'), TO_CHAR(FECHA_HORA_FIN, 'HH24:MI')
                HAVING COUNT(DISTINCT TRUNC(CAST(FECHA_HORA_INICIO AS DATE))) >= 2
                 ORDER BY DECODE(DIA_SEMANA,'LUNES',1,'MARTES',2,'MIERCOLES',3,'JUEVES',4,'VIERNES',5,'SABADO',6,7), DESDE
                """,
                (rs, i) -> new BloqueDto(rs.getString("DIA_SEMANA"), rs.getString("DESDE"), rs.getString("HASTA")),
                medico);
        return ResponseEntity.ok(bloques);
    }

    /** Reemplaza el horario semanal y regenera las agendas (se repiten cada semana, ~1 anio por delante; el job las sigue extendiendo). */
    @PutMapping("/semanal")
    public ResponseEntity<?> guardarSemanal(@RequestBody SemanalRequest req, @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, req.idMedico());
        if (medico == null) return sinMedico();
        String bloques = req.bloques() == null ? "" : String.join("|",
                req.bloques().stream().map(b -> b.dia() + "~" + b.desde() + "~" + b.hasta()).toList());

        Long creadas = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.GUARDAR_HORARIO_SEMANAL(?,?,?,?,?)}")) {
                cs.setLong(1, medico);
                cs.setString(2, bloques);
                cs.setLong(3, user.idUsuario());
                cs.setInt(4, 52);
                cs.registerOutParameter(5, Types.NUMERIC);
                cs.execute();
                return cs.getLong(5);
            }
        });
        return ResponseEntity.ok(Map.of("creadas", creadas));
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody CrearRequest req, @AuthenticationPrincipal AuthUser user) {
        Long medico = medicoDe(user, req.idMedico());
        if (medico == null) return sinMedico();
        if (req.fecha() == null || req.desde() == null || req.hasta() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("mensaje", "Faltan datos: fecha, hora desde y hora hasta"));
        }
        Timestamp inicio = Timestamp.valueOf(req.fecha().atTime(req.desde()));
        Timestamp fin = Timestamp.valueOf(req.fecha().atTime(req.hasta()));

        Long idAgenda = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.CREAR_AGENDA(?,?,?,?,?)}")) {
                cs.setLong(1, medico);
                cs.setTimestamp(2, inicio);
                cs.setTimestamp(3, fin);
                cs.setLong(4, user.idUsuario());
                cs.registerOutParameter(5, Types.NUMERIC);
                cs.execute();
                return cs.getLong(5);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idAgenda", idAgenda));
    }

    @DeleteMapping("/{idAgenda}")
    public ResponseEntity<?> eliminar(@PathVariable Long idAgenda, @AuthenticationPrincipal AuthUser user) {
        // El medico solo borra lo suyo (el paquete lo verifica); el superadmin pasa NULL
        Long soloDe = "MEDICO".equals(user.rol()) ? user.idEntidad() : null;
        if ("MEDICO".equals(user.rol()) && soloDe == null) return sinMedico();

        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.ELIMINAR_AGENDA(?,?,?)}")) {
                cs.setLong(1, idAgenda);
                cs.setLong(2, user.idUsuario());
                if (soloDe == null) cs.setNull(3, Types.NUMERIC); else cs.setLong(3, soloDe);
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.ok(Map.of("mensaje", "Horario eliminado"));
    }
}
