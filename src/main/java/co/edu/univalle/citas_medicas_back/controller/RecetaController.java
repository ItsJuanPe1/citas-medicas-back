package co.edu.univalle.citas_medicas_back.controller;

import co.edu.univalle.citas_medicas_back.config.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** HU05 (parte del medico): prescribir una receta a partir de una historia clinica. */
@RestController
public class RecetaController {

    public record ItemRequest(Long idMedicamento, Integer cantidad, String dosis) {}
    public record RecetaRequest(Long idHistoria, List<ItemRequest> items) {}
    public record MedicamentoDto(Long id, String nombre, String presentacion, String tipo, Long disponible) {}

    private final JdbcTemplate jdbc;

    public RecetaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Catalogo para elegir medicamentos, con el stock actual. */
    @GetMapping("/api/medicamentos")
    public List<MedicamentoDto> medicamentos() {
        return jdbc.query("""
                SELECT m.ID_MEDICAMENTO, m.NOMBRE, m.PRESENTACION, t.VALOR AS TIPO,
                       NVL(s.CANTIDAD_DISPONIBLE, 0) AS DISPONIBLE
                  FROM TBL_MEDICAMENTOS m
                  JOIN TBL_PARAMETRICA_TIPOS t ON t.ID_PARAMETRO = m.ID_TIPO_MEDICAMENTO
                  LEFT JOIN TBL_STOCK_FARMACIA s ON s.ID_MEDICAMENTO = m.ID_MEDICAMENTO
                 ORDER BY m.NOMBRE
                """,
                (rs, i) -> new MedicamentoDto(rs.getLong("ID_MEDICAMENTO"), rs.getString("NOMBRE"),
                        rs.getString("PRESENTACION"), rs.getString("TIPO"), rs.getLong("DISPONIBLE")));
    }

    @PostMapping("/api/recetas")
    public ResponseEntity<?> prescribir(@RequestBody RecetaRequest req, @AuthenticationPrincipal AuthUser user) {
        if (req.idHistoria() == null || req.items() == null || req.items().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("mensaje", "La receta debe tener al menos un medicamento"));
        }
        // El medico solo receta sobre sus propias consultas; el superadmin, cualquiera.
        Long idMedico = "MEDICO".equals(user.rol()) ? user.idEntidad() : null;
        if ("MEDICO".equals(user.rol()) && idMedico == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("mensaje", "El usuario no tiene un medico asociado"));
        }
        for (ItemRequest it : req.items()) {
            if (it.idMedicamento() == null || it.cantidad() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("mensaje", "Cada medicamento necesita su cantidad"));
            }
        }
        // id~cantidad~dosis separados por '|'. Se quitan los separadores del texto libre.
        String items = req.items().stream()
                .map(it -> it.idMedicamento() + "~" + it.cantidad() + "~"
                        + (it.dosis() == null ? "" : it.dosis().replace("|", " ").replace("~", " ").trim()))
                .collect(Collectors.joining("|"));

        Long idReceta = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_MEDICAMENTOS.PRO_PRESCRIBIR_RECETA(?,?,?,?,?)}")) {
                cs.setLong(1, req.idHistoria());
                cs.setString(2, items);
                cs.setLong(3, user.idUsuario());
                cs.registerOutParameter(4, Types.NUMERIC);
                if (idMedico == null) cs.setNull(5, Types.NUMERIC); else cs.setLong(5, idMedico);
                cs.execute();
                return cs.getLong(4);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idReceta", idReceta));
    }
}
