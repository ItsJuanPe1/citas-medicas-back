package co.edu.univalle.citas_medicas_back.controller;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/medicos")
public class MedicoController {

    public record MedicoDto(Long id, String nombre, String apellido, String especialidad) {
    }

    private final JdbcTemplate jdbc;

    public MedicoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<MedicoDto> listar() {
        String sql = """
                SELECT m.ID_MEDICO, m.NOMBRE, m.APELLIDO, pt.VALOR AS ESPECIALIDAD
                FROM MEDICOS m
                JOIN PARAMETRICA_TIPOS pt ON pt.ID_PARAMETRO = m.ID_ESPECIALIDAD
                ORDER BY m.APELLIDO
                """;
        return jdbc.query(sql, (rs, i) -> new MedicoDto(
                rs.getLong("ID_MEDICO"),
                rs.getString("NOMBRE"),
                rs.getString("APELLIDO"),
                rs.getString("ESPECIALIDAD")));
    }
}