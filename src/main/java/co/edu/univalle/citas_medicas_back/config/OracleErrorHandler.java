package co.edu.univalle.citas_medicas_back.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Map;

/**
 * Convierte los errores de negocio de PL/SQL (RAISE_APPLICATION_ERROR -20000..-20999)
 * en respuestas HTTP con {"mensaje": "..."}. Los controllers NO atrapan estos errores:
 * los dejan subir hasta aqui.
 *
 * Para dar un estado HTTP especifico a un codigo nuevo, agregalo en ESTADOS.
 * Lo que no este en la tabla responde 400.
 */
@RestControllerAdvice
public class OracleErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(OracleErrorHandler.class);

    // Rangos por paquete: citas 20001-20099, historias 20100-20199, medicamentos 20200-20299, agendas 20300-20399, perfiles 20400-20499, usuarios 20500-20599
    private static final Map<Integer, HttpStatus> ESTADOS = Map.ofEntries(
            // PKG_GESTION_CITAS
            Map.entry(20001, HttpStatus.CONFLICT),   // medico/horario ocupado
            Map.entry(20002, HttpStatus.NOT_FOUND),  // horario no existe
            Map.entry(20003, HttpStatus.NOT_FOUND),  // paciente no existe
            Map.entry(20004, HttpStatus.BAD_REQUEST),// horario pasado
            Map.entry(20005, HttpStatus.CONFLICT),   // paciente con cruce
            Map.entry(20006, HttpStatus.NOT_FOUND),  // cita no existe
            Map.entry(20007, HttpStatus.CONFLICT),   // cita no cancelable
            Map.entry(20008, HttpStatus.FORBIDDEN),  // cita ajena
            Map.entry(20009, HttpStatus.BAD_REQUEST),// motivo de cancelacion obligatorio
            Map.entry(20010, HttpStatus.BAD_REQUEST),// duracion/hora de la cita invalida
            Map.entry(20011, HttpStatus.BAD_REQUEST),// cita fuera del horario del medico
            // PKG_HISTORIAS_CLINICAS (20100+)
            Map.entry(20101, HttpStatus.NOT_FOUND),  // cita no existe
            Map.entry(20102, HttpStatus.CONFLICT),   // cita no esta PROGRAMADA
            Map.entry(20103, HttpStatus.FORBIDDEN),  // cita de otro medico
            Map.entry(20104, HttpStatus.BAD_REQUEST),// diagnostico obligatorio
            Map.entry(20105, HttpStatus.BAD_REQUEST),// texto muy largo
            Map.entry(20106, HttpStatus.CONFLICT),   // cita vencida (horario ya termino)
            Map.entry(20107, HttpStatus.NOT_FOUND),  // trigger HU04: cita no existe
            Map.entry(20108, HttpStatus.CONFLICT),   // trigger HU04: cita no PROGRAMADA
            Map.entry(20109, HttpStatus.CONFLICT),   // la cita aun no inicia
            // PKG_AGENDAS (20300+)
            Map.entry(20301, HttpStatus.BAD_REQUEST),// horario en el pasado
            Map.entry(20302, HttpStatus.BAD_REQUEST),// rango invalido
            Map.entry(20303, HttpStatus.BAD_REQUEST),// duracion invalida
            Map.entry(20304, HttpStatus.BAD_REQUEST),// demasiadas franjas
            Map.entry(20305, HttpStatus.NOT_FOUND),  // medico no existe
            Map.entry(20306, HttpStatus.CONFLICT),   // bloqueado por otra persona
            Map.entry(20307, HttpStatus.CONFLICT),   // cruce con otros horarios
            Map.entry(20308, HttpStatus.NOT_FOUND),  // horario no existe
            Map.entry(20309, HttpStatus.FORBIDDEN),  // horario de otro medico
            Map.entry(20310, HttpStatus.CONFLICT),   // horario con cita
            // PKG_PERFILES (20400+)
            Map.entry(20401, HttpStatus.BAD_REQUEST),// sin perfil editable
            Map.entry(20402, HttpStatus.BAD_REQUEST),// correo invalido
            Map.entry(20403, HttpStatus.BAD_REQUEST),// telefono invalido
            Map.entry(20404, HttpStatus.NOT_FOUND),  // perfil no existe
            Map.entry(20405, HttpStatus.CONFLICT),   // correo en uso
            // PKG_GESTION_MEDICAMENTOS (20200+)
            Map.entry(20201, HttpStatus.NOT_FOUND),  // historia no existe
            Map.entry(20202, HttpStatus.FORBIDDEN),  // historia de otro medico
            Map.entry(20203, HttpStatus.CONFLICT),   // la consulta ya tiene receta
            Map.entry(20204, HttpStatus.BAD_REQUEST),// receta invalida
            Map.entry(20205, HttpStatus.NOT_FOUND),  // medicamento no existe
            Map.entry(20206, HttpStatus.NOT_FOUND),  // detalle no existe
            Map.entry(20207, HttpStatus.CONFLICT),   // sin stock
            Map.entry(20208, HttpStatus.CONFLICT),   // ya entregado
            Map.entry(20209, HttpStatus.CONFLICT),   // bloqueado por otra persona
            Map.entry(20210, HttpStatus.BAD_REQUEST),// cantidad invalida
            // PKG_USUARIOS (20500+)
            Map.entry(20501, HttpStatus.CONFLICT),   // usuario ya en uso
            Map.entry(20502, HttpStatus.CONFLICT),   // documento ya registrado
            Map.entry(20503, HttpStatus.CONFLICT),   // correo ya registrado
            Map.entry(20504, HttpStatus.BAD_REQUEST),// datos invalidos
            Map.entry(20505, HttpStatus.BAD_REQUEST),// contrasena debil
            Map.entry(20506, HttpStatus.BAD_REQUEST),// rol invalido
            Map.entry(20507, HttpStatus.BAD_REQUEST),// especialidad invalida
            Map.entry(20508, HttpStatus.NOT_FOUND),  // usuario no existe
            Map.entry(20509, HttpStatus.BAD_REQUEST),// estado invalido / auto-desactivacion
            Map.entry(20510, HttpStatus.CONFLICT),   // bloqueado por otra persona
            Map.entry(20511, HttpStatus.BAD_REQUEST),// clave actual incorrecta (400, no 401: el front no cierra sesion)
            Map.entry(20512, HttpStatus.BAD_REQUEST) // nueva clave igual a la actual
            // Siguiente paquete: poner una coma al final de la linea de arriba y agregar aqui (sin coma en la ultima)
    );

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> oracle(DataAccessException e) {
        SQLException sql = buscarErrorDeNegocio(e);
        if (sql != null) {
            HttpStatus status = ESTADOS.getOrDefault(sql.getErrorCode(), HttpStatus.BAD_REQUEST);
            // "ORA-20001: mensaje\nORA-06512: ..." -> solo el mensaje
            String msg = sql.getMessage().split("\n")[0].replaceFirst("^ORA-\\d+:\\s*", "");
            return ResponseEntity.status(status).body(Map.of("mensaje", msg));
        }
        log.error("Error de base de datos", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("mensaje", "Error interno del servidor"));
    }

    /**
     * Recorre la cadena de causas y devuelve el primer SQLException con codigo ORA-20000..20999.
     * (El driver de Oracle puede colgar otra excepcion como causa final, por eso no basta
     * con mirar la causa mas profunda.)
     */
    private static SQLException buscarErrorDeNegocio(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() >= 20000 && sql.getErrorCode() <= 20999) {
                return sql;
            }
        }
        return null;
    }
}
