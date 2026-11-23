package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/** Stock de la farmacia: listado y reposicion. Ajusta el import de AuthUser a tu package. */
@RestController
@RequestMapping("/api/farmacia/stock")
public class StockController {

    public record StockDto(Long idMedicamento, String medicamento, String presentacion, Long disponible, Long minimo) {}

    public record ReposicionRequest(Integer cantidad) {}

    private final JdbcTemplate jdbc;

    public StockController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<StockDto> listar(@RequestParam(required = false) String q) {
        String filtro = q == null ? "" : q.trim().toLowerCase();
        if (filtro.isEmpty()) {
            return jdbc.query("""
                    SELECT m.ID_MEDICAMENTO, m.NOMBRE, m.PRESENTACION, s.CANTIDAD_DISPONIBLE, s.CANTIDAD_MINIMA
                      FROM TBL_STOCK_FARMACIA s JOIN TBL_MEDICAMENTOS m ON m.ID_MEDICAMENTO = s.ID_MEDICAMENTO
                     ORDER BY (CASE WHEN s.CANTIDAD_DISPONIBLE < s.CANTIDAD_MINIMA THEN 0 ELSE 1 END), m.NOMBRE
                     FETCH FIRST 300 ROWS ONLY
                    """,
                    (rs, i) -> new StockDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)));
        }
        return jdbc.query("""
                SELECT m.ID_MEDICAMENTO, m.NOMBRE, m.PRESENTACION, s.CANTIDAD_DISPONIBLE, s.CANTIDAD_MINIMA
                  FROM TBL_STOCK_FARMACIA s JOIN TBL_MEDICAMENTOS m ON m.ID_MEDICAMENTO = s.ID_MEDICAMENTO
                 WHERE LOWER(m.NOMBRE) LIKE ?
                 ORDER BY (CASE WHEN s.CANTIDAD_DISPONIBLE < s.CANTIDAD_MINIMA THEN 0 ELSE 1 END), m.NOMBRE
                 FETCH FIRST 300 ROWS ONLY
                """,
                (rs, i) -> new StockDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)),
                "%" + filtro + "%");
    }

    @PostMapping("/{idMedicamento}/reposicion")
    public ResponseEntity<?> reponer(@PathVariable Long idMedicamento, @RequestBody ReposicionRequest req,
                                     @AuthenticationPrincipal AuthUser user) {
        Long disponible = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_MEDICAMENTOS.PRO_REPONER_STOCK(?,?,?,?)}")) {
                cs.setLong(1, idMedicamento);
                if (req.cantidad() == null) cs.setNull(2, Types.NUMERIC); else cs.setInt(2, req.cantidad());
                cs.setLong(3, user.idUsuario());
                cs.registerOutParameter(4, Types.NUMERIC);
                cs.execute();
                return cs.getLong(4);
            }
        });
        return ResponseEntity.ok(Map.of("disponible", disponible));
    }
}
