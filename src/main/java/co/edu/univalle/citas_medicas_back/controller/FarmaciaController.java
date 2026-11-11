package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HU05 (parte de la farmacia): medicamentos pendientes de entrega y registro de la entrega. */
@RestController
@RequestMapping("/api/farmacia")
public class FarmaciaController {

    public record PendienteDto(Long idDetalle, String paciente, String documento, String medicamento,
                               String presentacion, String dosis, Integer cantidad, Integer pendiente,
                               Long stock, LocalDateTime emitida) {}

    public record EntregaRequest(Integer cantidad) {}

    private final JdbcTemplate jdbc;

    public FarmaciaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Medicamentos con cantidad pendiente, los mas antiguos primero. documento filtra por paciente. */
    @GetMapping("/pendientes")
    public List<PendienteDto> pendientes(@RequestParam(required = false) String documento) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.ID_DETALLE, p.NOMBRE || ' ' || p.APELLIDO AS PACIENTE, p.DOCUMENTO,
                       m.NOMBRE AS MEDICAMENTO, m.PRESENTACION, d.DOSIS, d.CANTIDAD, d.CANTIDAD_PENDIENTE,
                       NVL(s.CANTIDAD_DISPONIBLE, 0) AS STOCK, r.FECHA_EMISION
                  FROM TBL_DETALLE_RECETA d
                  JOIN TBL_RECETAS_MEDICAS r ON r.ID_RECETA = d.ID_RECETA
                  JOIN TBL_HISTORIA_CLINICA h ON h.ID_HISTORIA = r.ID_HISTORIA
                  JOIN TBL_CITAS_MEDICAS c    ON c.ID_CITA = h.ID_CITA
                  JOIN TBL_PACIENTES p        ON p.ID_PACIENTE = c.ID_PACIENTE
                  JOIN TBL_MEDICAMENTOS m     ON m.ID_MEDICAMENTO = d.ID_MEDICAMENTO
                  LEFT JOIN TBL_STOCK_FARMACIA s ON s.ID_MEDICAMENTO = d.ID_MEDICAMENTO
                 WHERE d.CANTIDAD_PENDIENTE > 0
                """);
        List<Object> args = new ArrayList<>();
        if (documento != null && !documento.isBlank()) {
            sql.append(" AND p.DOCUMENTO LIKE ? ");
            args.add(documento.trim() + "%");
        }
        sql.append(" ORDER BY r.FECHA_EMISION, d.ID_DETALLE FETCH FIRST 200 ROWS ONLY");

        return jdbc.query(sql.toString(), (rs, i) -> new PendienteDto(
                rs.getLong("ID_DETALLE"), rs.getString("PACIENTE"), rs.getString("DOCUMENTO"),
                rs.getString("MEDICAMENTO"), rs.getString("PRESENTACION"), rs.getString("DOSIS"),
                rs.getInt("CANTIDAD"), rs.getInt("CANTIDAD_PENDIENTE"), rs.getLong("STOCK"),
                rs.getTimestamp("FECHA_EMISION").toLocalDateTime()), args.toArray());
    }

    /** Entrega la cantidad pedida (o todo lo que alcance, si no se indica) y descuenta el stock. */
    @PostMapping("/entregas/{idDetalle}")
    public ResponseEntity<?> entregar(@PathVariable Long idDetalle,
                                      @RequestBody(required = false) EntregaRequest req,
                                      @AuthenticationPrincipal AuthUser user) {
        Integer cantidad = req == null ? null : req.cantidad();
        // El farmaceutico queda registrado en el detalle; el superadmin entrega sin farmaceutico.
        Long idFarmaceutico = "FARMACEUTICO".equals(user.rol()) ? user.idEntidad() : null;

        Map<String, Long> r = jdbc.execute((ConnectionCallback<Map<String, Long>>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_MEDICAMENTOS.PRO_ENTREGAR_MEDICAMENTO(?,?,?,?,?,?)}")) {
                cs.setLong(1, idDetalle);
                cs.setLong(2, user.idUsuario());
                if (idFarmaceutico == null) cs.setNull(3, Types.NUMERIC); else cs.setLong(3, idFarmaceutico);
                cs.registerOutParameter(4, Types.NUMERIC);
                cs.registerOutParameter(5, Types.NUMERIC);
                if (cantidad == null) cs.setNull(6, Types.NUMERIC); else cs.setInt(6, cantidad);
                cs.execute();
                return Map.of("entregadas", cs.getLong(4), "pendientes", cs.getLong(5));
            }
        });
        return ResponseEntity.ok(r);
    }
}
