package com.planus.backend.global.security;

import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
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

    /**
     * 토큰을 한 번만 파싱해 서명·만료를 검증하고 userId를 추출한다.
     *
     * <p>subject가 숫자가 아닌 경우({@link NumberFormatException})도 {@link IllegalArgumentException}의
     * 하위 클래스이므로 INVALID_TOKEN으로 매핑된다.</p>
     *
     * @param token 검증할 JWT 토큰
     * @return 토큰에 담긴 userId
     * @throws GeneralException 만료된 토큰이면 EXPIRED_TOKEN, 그 외 오류면 INVALID_TOKEN
     */
    public Long parseUserId(String token) {
        try {
            return Long.parseLong(parseClaims(token).getSubject());
        } catch (ExpiredJwtException e) {
            throw new GeneralException(GeneralErrorCode.EXPIRED_TOKEN, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new GeneralException(GeneralErrorCode.INVALID_TOKEN, e);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
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
