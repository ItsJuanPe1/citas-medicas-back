package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Vistas de consulta para SUPERADMIN y GERENTE. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record CitaAdminDto(Long idCita, String paciente, String documento, String medico,
            String especialidad, LocalDateTime inicio, String estado, String motivo) {
    }

    public record LogDto(Long idLog, String usuario, String accion, String tabla, Long idRegistro,
            String antes, String despues, LocalDateTime fecha) {
    }

    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/citas")
    public List<CitaAdminDto> citas(@RequestParam(required = false) String estado,
            @RequestParam(required = false) Long idMedico,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        StringBuilder sql = new StringBuilder(
                """
                        SELECT c.ID_CITA, p.NOMBRE || ' ' || p.APELLIDO AS PACIENTE, p.DOCUMENTO,
                               m.NOMBRE || ' ' || m.APELLIDO AS MEDICO, esp.VALOR AS ESPECIALIDAD,
                               a.FECHA_HORA_INICIO, est.VALOR AS ESTADO, (SELECT REGEXP_SUBSTR(l.VALOR_DESPUES, 'motivo=(.*)$', 1, 1, 'n', 1)
                                  FROM LOG_AUDITORIAS l
                                 WHERE l.TABLA_AFECTADA = 'CITAS_MEDICAS' AND l.ACCION = 'CANCELAR_CITA'
                                   AND l.ID_REGISTRO = c.ID_CITA AND ROWNUM = 1) AS MOTIVO_CANCELACION
                          FROM CITAS_MEDICAS c
                          JOIN PACIENTES p           ON p.ID_PACIENTE = c.ID_PACIENTE
                          JOIN AGENDAS_MEDICOS a     ON a.ID_AGENDA = c.ID_AGENDA
                          JOIN MEDICOS m             ON m.ID_MEDICO = a.ID_MEDICO
                          JOIN PARAMETRICA_TIPOS esp ON esp.ID_PARAMETRO = m.ID_ESPECIALIDAD
                          JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = c.ID_ESTADO
                         WHERE 1 = 1
                        """);
        List<Object> args = new ArrayList<>();
        if (estado != null && !estado.isBlank()) {
            sql.append(" AND est.VALOR = ? ");
            args.add(estado);
        }
        if (idMedico != null) {
            sql.append(" AND a.ID_MEDICO = ? ");
            args.add(idMedico);
        }
        if (desde != null) {
            sql.append(" AND a.FECHA_HORA_INICIO >= ? ");
            args.add(Timestamp.valueOf(desde.atStartOfDay()));
        }
        if (hasta != null) {
            sql.append(" AND a.FECHA_HORA_INICIO < ? ");
            args.add(Timestamp.valueOf(hasta.plusDays(1).atStartOfDay()));
        }
        sql.append(" ORDER BY a.FECHA_HORA_INICIO DESC FETCH FIRST 200 ROWS ONLY");

        return jdbc.query(sql.toString(), (rs, i) -> new CitaAdminDto(
                rs.getLong("ID_CITA"), rs.getString("PACIENTE"), rs.getString("DOCUMENTO"),
                rs.getString("MEDICO"), rs.getString("ESPECIALIDAD"),
                rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(), rs.getString("ESTADO"),
                rs.getString("MOTIVO_CANCELACION")), args.toArray());
    }

    private static final Pattern P_AGENDA = Pattern.compile("id_agenda=(\\d+)");
    private static final Pattern P_NUEVO = Pattern.compile("nuevo_horario=(\\d+)");
    private static final Pattern P_PACIENTE = Pattern.compile("id_paciente=(\\d+)");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEE d MMM HH:mm",
            Locale.forLanguageTag("es-CO"));

    @GetMapping("/auditoria")
    public List<LogDto> auditoria() {
        List<LogDto> crudos = jdbc.query("""
                SELECT l.ID_LOG, u.USERNAME, l.ACCION, l.TABLA_AFECTADA, l.ID_REGISTRO,
                       l.VALOR_ANTES, l.VALOR_DESPUES, l.FECHA_HORA
                  FROM LOG_AUDITORIAS l
                  LEFT JOIN USUARIOS u ON u.ID_USUARIO = l.ID_USUARIO
                 ORDER BY l.FECHA_HORA DESC, l.ID_LOG DESC
                 FETCH FIRST 100 ROWS ONLY
                """,
                (rs, i) -> new LogDto(rs.getLong("ID_LOG"), rs.getString("USERNAME"), rs.getString("ACCION"),
                        rs.getString("TABLA_AFECTADA"), rs.getLong("ID_REGISTRO"),
                        rs.getString("VALOR_ANTES"), rs.getString("VALOR_DESPUES"),
                        rs.getTimestamp("FECHA_HORA").toLocalDateTime()));

        // Reemplaza ids (horario, paciente) por texto legible: nombre y fecha en vez de
        // numeros.
        Set<Long> idsAgenda = new HashSet<>();
        Set<Long> idsPaciente = new HashSet<>();
        for (LogDto l : crudos) {
            for (String t : new String[] { l.antes(), l.despues() }) {
                if (t == null)
                    continue;
                recolectar(P_AGENDA, t, idsAgenda);
                recolectar(P_NUEVO, t, idsAgenda);
                recolectar(P_PACIENTE, t, idsPaciente);
            }
        }
        Map<Long, String> horarios = horarios(idsAgenda);
        Map<Long, String> pacientes = pacientes(idsPaciente);

        return crudos.stream().map(l -> new LogDto(l.idLog(), l.usuario(), l.accion(), l.tabla(), l.idRegistro(),
                legible(l.antes(), horarios, pacientes), legible(l.despues(), horarios, pacientes), l.fecha()))
                .toList();
    }

    private static void recolectar(Pattern p, String texto, Set<Long> destino) {
        Matcher m = p.matcher(texto);
        while (m.find())
            destino.add(Long.parseLong(m.group(1)));
    }

    private static String legible(String texto, Map<Long, String> horarios, Map<Long, String> pacientes) {
        if (texto == null)
            return null;
        String r = P_PACIENTE.matcher(texto).replaceAll(m -> Matcher.quoteReplacement(
                "paciente=" + pacientes.getOrDefault(Long.parseLong(m.group(1)), "#" + m.group(1))));
        r = P_AGENDA.matcher(r).replaceAll(m -> Matcher.quoteReplacement(
                "horario=" + horarios.getOrDefault(Long.parseLong(m.group(1)), "#" + m.group(1))));
        r = P_NUEVO.matcher(r).replaceAll(m -> Matcher.quoteReplacement(
                "horario liberado=" + horarios.getOrDefault(Long.parseLong(m.group(1)), "#" + m.group(1))));
        return r;
    }

    private Map<Long, String> horarios(Set<Long> ids) {
        Map<Long, String> res = new HashMap<>();
        if (ids.isEmpty())
            return res;
        String marcas = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        jdbc.query("""
                SELECT a.ID_AGENDA, a.FECHA_HORA_INICIO, m.NOMBRE || ' ' || m.APELLIDO AS MEDICO
                  FROM AGENDAS_MEDICOS a JOIN MEDICOS m ON m.ID_MEDICO = a.ID_MEDICO
                 WHERE a.ID_AGENDA IN (""" + marcas + ")",
                rs -> {
                    res.put(rs.getLong(1), rs.getString(3) + ", "
                            + FECHA.format(rs.getTimestamp(2).toLocalDateTime()));
                }, ids.toArray());
        return res;
    }

    private Map<Long, String> pacientes(Set<Long> ids) {
        Map<Long, String> res = new HashMap<>();
        if (ids.isEmpty())
            return res;
        String marcas = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT ID_PACIENTE, NOMBRE || ' ' || APELLIDO FROM PACIENTES WHERE ID_PACIENTE IN (" + marcas + ")",
                rs -> {
                    res.put(rs.getLong(1), rs.getString(2));
                }, ids.toArray());
        return res;
    }
}
