package co.edu.univalle.citas_medicas_back.controller;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** HU10: reporte de atencion y prescripcion de un periodo (gerente y superadmin). */
@RestController
@RequestMapping("/api/reportes")
public class ReporteController {

    public record MedicoFila(Long idMedico, String medico, String especialidad,
                             int atendidas, int canceladas, int noAsistio, int programadas, int recetas) {}

    public record Resumen(int programadas, int atendidas, int canceladas, int noAsistio,
                          int recetas, int recetasEntregadas, int recetasParciales, int recetasNoEntregadas) {}

    public record Reporte(LocalDate desde, LocalDate hasta, Resumen resumen, List<MedicoFila> medicos) {}

    private final JdbcTemplate jdbc;

    public ReporteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/periodo")
    public Reporte periodo(@RequestParam LocalDate desde, @RequestParam LocalDate hasta) {
        return jdbc.execute((ConnectionCallback<Reporte>) con -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call PKG_REPORTES.PRO_REPORTE_PERIODO(?,?,?,?,?,?,?,?,?,?,?)}")) {
                cs.setDate(1, Date.valueOf(desde));
                cs.setDate(2, Date.valueOf(hasta));
                for (int i = 3; i <= 10; i++) cs.registerOutParameter(i, Types.NUMERIC);
                cs.registerOutParameter(11, Types.REF_CURSOR);
                cs.execute();

                List<MedicoFila> medicos = new ArrayList<>();
                try (ResultSet rs = cs.getObject(11, ResultSet.class)) {
                    while (rs.next()) {
                        medicos.add(new MedicoFila(rs.getLong("ID_MEDICO"), rs.getString("MEDICO"), rs.getString("ESPECIALIDAD"),
                                rs.getInt("ATENDIDAS"), rs.getInt("CANCELADAS"), rs.getInt("NO_ASISTIO"),
                                rs.getInt("PROGRAMADAS"), rs.getInt("RECETAS")));
                    }
                }
                Resumen r = new Resumen(cs.getInt(3), cs.getInt(4), cs.getInt(5), cs.getInt(6),
                        cs.getInt(7), cs.getInt(8), cs.getInt(9), cs.getInt(10));
                return new Reporte(desde, hasta, r, medicos);
            }
        });
    }
}
