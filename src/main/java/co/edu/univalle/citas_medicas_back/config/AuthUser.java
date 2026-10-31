package co.edu.univalle.citas_medicas_back.config;

/** Datos del usuario autenticado, leidos del JWT. */
public record AuthUser(long idUsuario, String username, String rol, Long idEntidad) {
}
