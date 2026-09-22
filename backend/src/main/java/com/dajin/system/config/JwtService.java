package com.dajin.system.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey key; private final long hours;
    public JwtService(@Value("${dajin.jwt-secret}") String secret, @Value("${dajin.jwt-hours:2}") long hours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.hours = hours;
    }
    public String issue(long userId, String username, String roleCode, long storeId) {
        Date now = new Date();
        return Jwts.builder().setSubject(String.valueOf(userId)).claim("username", username).claim("role", roleCode)
            .claim("storeId", storeId).setIssuedAt(now).setExpiration(new Date(now.getTime()+hours*3600_000L))
            .signWith(key, SignatureAlgorithm.HS256).compact();
    }
    public Claims parse(String token) { return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody(); }
}
