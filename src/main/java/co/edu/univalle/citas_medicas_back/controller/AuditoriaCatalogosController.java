package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Catalogos para que la pantalla de Auditoria muestre nombres en vez de ids
 * (estados, medicamentos y farmaceuticos). Solo SUPERADMIN (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/auditoria/catalogos")
public class AuditoriaCatalogosController {

    public record Catalogos(Map<Long, String> estados, Map<Long, String> medicamentos, Map<Long, String> farmaceuticos) {}

    private final JdbcTemplate jdbc;

    public AuditoriaCatalogosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Catalogos catalogos() {
        Map<Long, String> estados = new HashMap<>();
        jdbc.query("SELECT ID_PARAMETRO, VALOR FROM TBL_PARAMETRICA_TIPOS", rs -> {
            estados.put(rs.getLong(1), rs.getString(2));
        });
        Map<Long, String> medicamentos = new HashMap<>();
        jdbc.query("SELECT ID_MEDICAMENTO, NOMBRE FROM TBL_MEDICAMENTOS", rs -> {
            medicamentos.put(rs.getLong(1), rs.getString(2));
        });
        Map<Long, String> farmaceuticos = new HashMap<>();
        jdbc.query("SELECT ID_FARMACEUTICO, NOMBRE || ' ' || APELLIDO FROM TBL_FARMACEUTICOS", rs -> {
            farmaceuticos.put(rs.getLong(1), rs.getString(2));
        });
        return new Catalogos(estados, medicamentos, farmaceuticos);
    }
}
