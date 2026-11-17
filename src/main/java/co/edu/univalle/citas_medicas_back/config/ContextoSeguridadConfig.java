package co.edu.univalle.citas_medicas_back.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

/**
 * HU07: cada conexion que sale del pool recibe el rol y la entidad del usuario de la peticion
 * (PKG_SEGURIDAD.PRO_ESTABLECER_CONTEXTO). La politica DBMS_RLS de TBL_HISTORIA_CLINICA los lee.
 * Sin usuario autenticado (login, jobs) se limpia el contexto. Si falla, la conexion no se entrega
 * (no se deja pasar a nadie sin filtro).
 * Ajusta el import de AuthUser al package donde lo tienes.
 */
@Configuration
public class ContextoSeguridadConfig {

    @Bean
    static BeanPostProcessor contextoPorConexion() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof ConContexto)) {
                    return new ConContexto(ds);
                }
                return bean;
            }
        };
    }

    static final class ConContexto extends DelegatingDataSource {
        ConContexto(DataSource ds) {
            super(ds);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return aplicar(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String pass) throws SQLException {
            return aplicar(super.getConnection(user, pass));
        }

        private static Connection aplicar(Connection con) throws SQLException {
            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthUser u) {
                    try (CallableStatement cs = con.prepareCall("{call PKG_SEGURIDAD.PRO_ESTABLECER_CONTEXTO(?,?,?)}")) {
                        cs.setString(1, u.rol());
                        if (u.idEntidad() == null) cs.setNull(2, Types.NUMERIC); else cs.setLong(2, u.idEntidad());
                        cs.setLong(3, u.idUsuario());
                        cs.execute();
                    }
                } else {
                    try (CallableStatement cs = con.prepareCall("{call PKG_SEGURIDAD.PRO_LIMPIAR_CONTEXTO}")) {
                        cs.execute();
                    }
                }
                return con;
            } catch (SQLException | RuntimeException e) {
                con.close();
                throw e;
            }
        }
    }
}
