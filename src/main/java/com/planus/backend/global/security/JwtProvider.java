package com.planus.backend.global.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 액세스/리프레시 토큰 생성 컴포넌트.
 *
 * <p>토큰 서명에는 HMAC-SHA 알고리즘을 사용하며, 시크릿 키는 {@code planus.jwt.secret} 프로퍼티에서 주입된다.
 * 만료 시간은 각각 {@code access-token-expiry}, {@code refresh-token-expiry}(밀리초)로 설정한다.</p>
 */
@Component
public class JwtProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtProvider(
            @Value("${planus.jwt.secret}") String secret,
            @Value("${planus.jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${planus.jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    /**
     * 액세스 토큰을 생성한다. subject는 userId이며 만료 시간은 {@code access-token-expiry}를 따른다.
     *
     * @param userId 토큰 subject로 사용할 사용자 ID
     * @return 서명된 JWT 액세스 토큰
     */
    public String generateAccessToken(Long userId) {
        return buildToken(userId, accessTokenExpiry);
    }

    /**
     * 리프레시 토큰을 생성한다. subject는 userId이며 만료 시간은 {@code refresh-token-expiry}를 따른다.
     *
     * @param userId 토큰 subject로 사용할 사용자 ID
     * @return 서명된 JWT 리프레시 토큰
     */
    public String generateRefreshToken(Long userId) {
        return buildToken(userId, refreshTokenExpiry);
    }

    /**
     * 리프레시 토큰을 SHA-256으로 해시한다.
     *
     * <p>DB에는 토큰 원문 대신 해시만 저장하여, DB 유출 시 토큰 재사용을 방지한다.
     * refresh 요청 시 제출된 토큰을 동일하게 해시한 뒤 DB 값과 비교한다.</p>
     *
     * @param token 해시할 리프레시 토큰 원문
     * @return HEX 인코딩된 SHA-256 해시 문자열
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private String buildToken(Long userId, long expiryMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs))
                .signWith(secretKey)
                .compact();
    }
}
