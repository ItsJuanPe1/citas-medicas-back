package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agendas con espacio libre. Cada agenda es un rango de horas del medico; trae los tramos ya ocupados
 * por citas (no canceladas) para que la pantalla ofrezca solo los huecos libres.
 */
@RestController
@RequestMapping("/api/agendas")
public class AgendaController {

    public record Tramo(LocalDateTime inicio, LocalDateTime fin) {}
    public record HorarioDto(Long idAgenda, Long idMedico, String nombre, String apellido,
                             String especialidad, LocalDateTime inicio, LocalDateTime fin, List<Tramo> ocupados) {}

    private final JdbcTemplate jdbc;

    public AgendaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/disponibles")
    public List<HorarioDto> disponibles(@RequestParam(required = false) Long idMedico,
                                        @RequestParam(required = false) LocalDate desde,
                                        @RequestParam(required = false, defaultValue = "7") int dias) {
        // Ventana de consulta: por defecto la semana que empieza hoy; el paciente puede pedir otra fecha de inicio
        LocalDate inicioVentana = (desde == null || desde.isBefore(LocalDate.now())) ? LocalDate.now() : desde;
        int largo = Math.max(1, Math.min(dias, 31));
        StringBuilder sql = new StringBuilder("""
                SELECT a.ID_AGENDA, a.ID_MEDICO, m.NOMBRE, m.APELLIDO, esp.VALOR AS ESPECIALIDAD,
                       a.FECHA_HORA_INICIO, a.FECHA_HORA_FIN
                  FROM TBL_AGENDAS_MEDICOS a
                  JOIN TBL_MEDICOS m          ON m.ID_MEDICO = a.ID_MEDICO
                  JOIN TBL_PARAMETRICA_TIPOS esp ON esp.ID_PARAMETRO = m.ID_ESPECIALIDAD
                  JOIN TBL_PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = a.ID_ESTADO
                 WHERE est.NOMBRE = 'ESTADO_AGENDA' AND est.VALOR = 'DISPONIBLE'
                   AND a.FECHA_HORA_FIN > LOCALTIMESTAMP
                   AND a.FECHA_HORA_FIN > ?
                   AND a.FECHA_HORA_INICIO < ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(inicioVentana.atStartOfDay()));
        args.add(Timestamp.valueOf(inicioVentana.plusDays(largo).atStartOfDay()));
        if (idMedico != null) {
            sql.append(" AND a.ID_MEDICO = ? ");
            args.add(idMedico);
        }
        sql.append(" ORDER BY a.FECHA_HORA_INICIO FETCH FIRST 200 ROWS ONLY");

        List<Object[]> filas = jdbc.query(sql.toString(), (rs, i) -> new Object[] {
                rs.getLong("ID_AGENDA"), rs.getLong("ID_MEDICO"),
                rs.getString("NOMBRE"), rs.getString("APELLIDO"), rs.getString("ESPECIALIDAD"),
                rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(),
                rs.getTimestamp("FECHA_HORA_FIN").toLocalDateTime() }, args.toArray());
        if (filas.isEmpty()) return List.of();

        // Tramos ocupados de esas agendas
        String marcas = filas.stream().map(f -> "?").collect(Collectors.joining(","));
        Map<Long, List<Tramo>> ocupados = new HashMap<>();
        jdbc.query("""
                SELECT c.ID_AGENDA, c.FECHA_HORA_INICIO, c.FECHA_HORA_FIN
                  FROM TBL_CITAS_MEDICAS c
                  JOIN TBL_PARAMETRICA_TIPOS e ON e.ID_PARAMETRO = c.ID_ESTADO
                 WHERE e.VALOR <> 'CANCELADA' AND c.ID_AGENDA IN (""" + marcas + ") ORDER BY c.FECHA_HORA_INICIO",
                rs -> {
                    ocupados.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Tramo(rs.getTimestamp(2).toLocalDateTime(), rs.getTimestamp(3).toLocalDateTime()));
                }, filas.stream().map(f -> f[0]).toArray());

        return filas.stream().map(f -> new HorarioDto((Long) f[0], (Long) f[1], (String) f[2], (String) f[3],
                (String) f[4], (LocalDateTime) f[5], (LocalDateTime) f[6],
                ocupados.getOrDefault((Long) f[0], List.of()))).toList();
    }
}
