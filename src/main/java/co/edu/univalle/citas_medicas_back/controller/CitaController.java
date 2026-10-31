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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * HU01 y HU02. Plantilla de referencia para los demas controllers:
 * - el rol y el usuario salen del token (AuthUser), nunca del body
 * - la logica vive en PL/SQL; aqui solo se llama al procedimiento
 * - los errores de negocio (ORA-200xx) los traduce OracleErrorHandler
 */
@RestController
@RequestMapping("/api/citas")
public class CitaController {

    public record ReservaRequest(Long idAgenda, Long idPaciente) {
    }

    public record CancelarRequest(String motivo) {
    }

    public record CitaDto(Long idCita, String nombre, String apellido, String especialidad,
            LocalDateTime inicio, String estado, String motivo) {
    }

    private final JdbcTemplate jdbc;

    public CitaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** HU01 */
    @PostMapping
    public ResponseEntity<?> reservar(@RequestBody ReservaRequest req, @AuthenticationPrincipal AuthUser user) {
        if (req.idAgenda() == null)
            return error(HttpStatus.BAD_REQUEST, "Falta el horario");
        // El paciente reserva para si mismo; recepcion y superadmin eligen al paciente.
        Long idPaciente = "PACIENTE".equals(user.rol()) ? user.idEntidad() : req.idPaciente();
        if (idPaciente == null)
            return error(HttpStatus.BAD_REQUEST, "Falta el paciente");

        Long idCita = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_CITAS.RESERVAR_CITA(?,?,?,?)}")) {
                cs.setLong(1, idPaciente);
                cs.setLong(2, req.idAgenda());
                cs.setLong(3, user.idUsuario());
                cs.registerOutParameter(4, Types.NUMERIC);
                cs.execute();
                return cs.getLong(4);
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idCita", idCita));
    }

    /**
     * Citas de un paciente. El paciente ve las suyas; los demas roles pasan
     * idPaciente.
     */
    @GetMapping
    public ResponseEntity<?> listar(@RequestParam(required = false) Long idPaciente,
            @AuthenticationPrincipal AuthUser user) {
        Long id = "PACIENTE".equals(user.rol()) ? user.idEntidad() : idPaciente;
        if (id == null)
            return error(HttpStatus.BAD_REQUEST, "Falta el paciente");

        List<CitaDto> citas = jdbc.query(
                """
                        SELECT c.ID_CITA, m.NOMBRE, m.APELLIDO, esp.VALOR AS ESPECIALIDAD,
                               a.FECHA_HORA_INICIO, est.VALOR AS ESTADO, (SELECT REGEXP_SUBSTR(l.VALOR_DESPUES, 'motivo=(.*)$', 1, 1, 'n', 1)
                                  FROM LOG_AUDITORIAS l
                                 WHERE l.TABLA_AFECTADA = 'CITAS_MEDICAS' AND l.ACCION = 'CANCELAR_CITA'
                                   AND l.ID_REGISTRO = c.ID_CITA AND ROWNUM = 1) AS MOTIVO_CANCELACION
                          FROM CITAS_MEDICAS c
                          JOIN AGENDAS_MEDICOS a     ON a.ID_AGENDA = c.ID_AGENDA
                          JOIN MEDICOS m             ON m.ID_MEDICO = a.ID_MEDICO
                          JOIN PARAMETRICA_TIPOS esp ON esp.ID_PARAMETRO = m.ID_ESPECIALIDAD
                          JOIN PARAMETRICA_TIPOS est ON est.ID_PARAMETRO = c.ID_ESTADO
                         WHERE c.ID_PACIENTE = ?
                         ORDER BY a.FECHA_HORA_INICIO DESC
                         FETCH FIRST 100 ROWS ONLY
                        """,
                (rs, i) -> new CitaDto(rs.getLong("ID_CITA"), rs.getString("NOMBRE"), rs.getString("APELLIDO"),
                        rs.getString("ESPECIALIDAD"), rs.getTimestamp("FECHA_HORA_INICIO").toLocalDateTime(),
                        rs.getString("ESTADO"), rs.getString("MOTIVO_CANCELACION")),
                id);
        return ResponseEntity.ok(citas);
    }

    /** HU02 */
    @PutMapping("/{id}/cancelar")
    public ResponseEntity<?> cancelar(@PathVariable long id, @RequestBody CancelarRequest req,
            @AuthenticationPrincipal AuthUser user) {
        // El paciente solo cancela las suyas; recepcion y superadmin cualquiera.
        Long idPaciente = "PACIENTE".equals(user.rol()) ? user.idEntidad() : null;

        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (CallableStatement cs = con.prepareCall("{call PKG_GESTION_CITAS.CANCELAR_CITA(?,?,?,?)}")) {
                cs.setLong(1, id);
                cs.setLong(2, user.idUsuario());
                cs.setString(3, req.motivo());
                if (idPaciente == null)
                    cs.setNull(4, Types.NUMERIC);
                else
                    cs.setLong(4, idPaciente);
                cs.execute();
                return null;
            }
        });
        return ResponseEntity.ok(Map.of("mensaje", "Cita cancelada"));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("mensaje", msg));
    }
}
