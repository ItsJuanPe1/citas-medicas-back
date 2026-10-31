package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pacientes")
public class PacienteController {

    public record PacienteDto(Long id, String documento, String nombre, String apellido) {
    }

    private final JdbcTemplate jdbc;

    public PacienteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Busqueda para la recepcionista: por documento, nombre o apellido. */
    @GetMapping
    public List<PacienteDto> buscar(@RequestParam String q) {
        String patron = "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("""
                SELECT ID_PACIENTE, DOCUMENTO, NOMBRE, APELLIDO
                  FROM PACIENTES
                 WHERE LOWER(DOCUMENTO || ' ' || NOMBRE || ' ' || APELLIDO) LIKE ?
                 ORDER BY APELLIDO, NOMBRE
                 FETCH FIRST 20 ROWS ONLY
                """,
                (rs, i) -> new PacienteDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                patron);
    }
}
