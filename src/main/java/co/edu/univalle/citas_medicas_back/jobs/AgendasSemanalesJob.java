package co.edu.univalle.citas_medicas_back.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.Types;

/**
 * Cada hora extiende el horario semanal de cada medico para que nunca se acabe
 * (siempre quedan ~52 semanas de agendas por delante). La logica vive en PKG_AGENDAS.PRO_GENERAR_AGENDAS_SEMANALES.
 * (@EnableScheduling ya lo activa NoAsistioJob.)
 */
@Component
public class AgendasSemanalesJob {

    private static final Logger log = LoggerFactory.getLogger(AgendasSemanalesJob.class);
    private final JdbcTemplate jdbc;

    public AgendasSemanalesJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 3_600_000)
    public void generar() {
        try {
            Long n = jdbc.execute((ConnectionCallback<Long>) con -> {
                try (CallableStatement cs = con.prepareCall("{call PKG_AGENDAS.PRO_GENERAR_AGENDAS_SEMANALES(?,?,?)}")) {
                    cs.setNull(1, Types.NUMERIC);
                    cs.setInt(2, 52);
                    cs.registerOutParameter(3, Types.NUMERIC);
                    cs.execute();
                    return cs.getLong(3);
                }
            });
            if (n != null && n > 0) log.info("Agendas semanales generadas: {}", n);
        } catch (Exception e) {
            log.warn("No se pudieron generar agendas semanales: {}", e.getMessage());
        }
    }
}
