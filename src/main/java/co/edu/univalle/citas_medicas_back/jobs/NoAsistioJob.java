package co.edu.univalle.citas_medicas_back.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.CallableStatement;
import java.sql.Types;

/**
 * Cada minuto pasa a NO_ASISTIO las citas PROGRAMADA cuyo horario ya termino.
 * La logica vive en PKG_HISTORIAS_CLINICAS.PRO_MARCAR_NO_ASISTIDAS; esto solo la dispara.
 */
@Configuration
@EnableScheduling
public class NoAsistioJob {

    private static final Logger log = LoggerFactory.getLogger(NoAsistioJob.class);
    private final JdbcTemplate jdbc;

    public NoAsistioJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    public void marcarVencidas() {
        try {
            Long n = jdbc.execute((ConnectionCallback<Long>) con -> {
                try (CallableStatement cs = con.prepareCall("{call PKG_HISTORIAS_CLINICAS.PRO_MARCAR_NO_ASISTIDAS(?)}")) {
                    cs.registerOutParameter(1, Types.NUMERIC);
                    cs.execute();
                    return cs.getLong(1);
                }
            });
            if (n != null && n > 0) log.info("Citas marcadas como NO_ASISTIO: {}", n);
        } catch (Exception e) {
            // Si falla (p. ej. paquete aun sin compilar) no debe tumbar el back
            log.warn("No se pudo marcar citas vencidas: {}", e.getMessage());
        }
    }
}
