package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agendas")
public class AgendaController {

    public record HorarioDto(Long idAgenda, Long idMedico, String nombre, String apellido,
            String especialidad, LocalDateTime inicio, LocalDateTime fin) {
    }

    private final JdbcTemplate jdbc;

    public AgendaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/disponibles")
    public List<HorarioDto> disponibles(@RequestParam(required = false) Long idMedico) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.ID_AGENDA, a.ID_MEDICO, m.NOMBRE, m.APELLIDO, esp.VALOR AS ESPECIALIDAD,
                       a.FECHA_HORA_INICIO, a.FECHA_HORA_FIN
                  FROM AGENDAS_MEDICOS a
                  JOIN MEDICOS m          ON m.ID_MEDICO = a.ID_MEDICO
                  JOIN PARAMETRICA_TIPOS esp ON esp.ID_PARAMETRO = m.ID_ESPECIALIDAD
                  JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = a.ID_ESTADO
                 WHERE est.NOMBRE = 'ESTADO_AGENDA' AND est.VALOR = 'DISPONIBLE'
                   AND a.FECHA_HORA_INICIO > LOCALTIMESTAMP
                """);
        List<Object> args = new ArrayList<>();
        if (idMedico != null) {
            sql.append(" AND a.ID_MEDICO = ? ");
            args.add(idMedico);
        }
        sql.append(" ORDER BY a.FECHA_HORA_INICIO FETCH FIRST 200 ROWS ONLY");

        return jdbc.query(sql.toString(), (rs, i) -> new HorarioDto(
                rs.getLong("ID_AGENDA"), rs.getLong("ID_MEDICO"),
                rs.getString("NOMBRE"), rs.getString("APELLIDO"), rs.getString("ESPECIALIDAD"),
                rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(),
                rs.getTimestamp("FECHA_HORA_FIN").toLocalDateTime()), args.toArray());
    }
}
