package co.edu.univalle.citas_medicas_back.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private static final long EXPIRA_MS = 8L * 60 * 60 * 1000; // 8 horas
    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generar(long idUsuario, String username, String rol, Long idEntidad) {
        Date ahora = new Date();
        JwtBuilder b = Jwts.builder()
                .subject(username)
                .claim("idUsuario", idUsuario)
                .claim("rol", rol);
        if (idEntidad != null)
            b.claim("idEntidad", idEntidad);
        return b.issuedAt(ahora)
                .expiration(new Date(ahora.getTime() + EXPIRA_MS))
                .signWith(key)
                .compact();
    }

    public Claims leer(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}