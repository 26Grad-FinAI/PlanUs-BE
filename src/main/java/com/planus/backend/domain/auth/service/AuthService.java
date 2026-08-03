package com.planus.backend.domain.auth.service;

import com.planus.backend.domain.auth.converter.AuthConverter;
import com.planus.backend.domain.auth.dto.LoginRequest;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 비즈니스 로직. 회원가입·로그인 처리 및 JWT 발급을 담당한다. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    /** 타이밍 공격 방지용 더미 BCrypt 해시. 이메일 미존재 시 비교에만 사용하며 실제 로그인에 쓰이지 않는다. */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    /**
     * 회원가입을 처리한다.
     *
     * <p>약관 동의 → 이메일 형식 → 이메일 중복 → 비밀번호 강도 → 비밀번호 일치 순으로 검증하며,
     * 검증 통과 후 BCrypt 암호화된 비밀번호로 사용자를 저장하고 JWT 토큰을 발급한다.</p>
     *
     * @param request 회원가입 요청 DTO
     * @return 가입된 사용자 정보 및 JWT 토큰
     */
    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        validateTerms(request.agreeToTerms());
        validateEmailFormat(request.email());
        validateEmailDuplicate(request.email());
        validatePasswordStrength(request.password());
        validatePasswordMatch(request.password(), request.confirmPassword());

        UserAccount user = AuthConverter.toUserAccount(request, passwordEncoder.encode(request.password()));
        UserAccount savedUser = userAccountRepository.save(user);

        String accessToken = jwtProvider.generateAccessToken(savedUser.getId());
        String refreshToken = jwtProvider.generateRefreshToken(savedUser.getId());
        savedUser.updateRefreshToken(jwtProvider.hashToken(refreshToken));

        return AuthConverter.toSignUpResponse(savedUser, accessToken, refreshToken);
    }

    /**
     * 이메일/비밀번호로 로그인을 처리한다.
     *
     * <p>이메일로 사용자를 조회하고 BCrypt 비밀번호를 검증한 뒤 JWT 토큰을 발급한다.
     * 소셜 로그인 사용자가 이메일/비밀번호로 로그인 시도 시 SOCIAL_LOGIN_EMAIL_CONFLICT를 반환한다.
     * 이메일 미존재 시에도 더미 해시로 BCrypt 비교를 수행해 응답 시간을 균일하게 유지하며,
     * 이메일 미존재와 비밀번호 불일치를 동일한 에러코드로 처리해 타이밍 공격과 계정 열거 공격을 방지한다.</p>
     *
     * @param request 이메일, 비밀번호
     * @return 사용자 정보 및 JWT 토큰
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        Optional<UserAccount> foundUser = userAccountRepository.findByEmail(request.email());

        if (foundUser.isPresent() && foundUser.get().getProvider() != AuthProvider.LOCAL) {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT);
        }

        String encodedPassword = foundUser.map(UserAccount::getPassword).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), encodedPassword);

        if (foundUser.isEmpty() || !passwordMatches) {
            throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS);
        }

        UserAccount user = foundUser.get();
        String accessToken = jwtProvider.generateAccessToken(user.getId());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());
        user.updateRefreshToken(jwtProvider.hashToken(refreshToken));

        return AuthConverter.toLoginResponse(user, accessToken, refreshToken);
    }

    private void validateTerms(boolean agreeToTerms) {
        if (!agreeToTerms) {
            throw new GeneralException(GeneralErrorCode.TERMS_NOT_AGREED);
        }
    }

    private void validateEmailFormat(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new GeneralException(GeneralErrorCode.INVALID_EMAIL_FORMAT);
        }
    }

    private void validateEmailDuplicate(String email) {
        if (userAccountRepository.existsByEmail(email)) {
            throw new GeneralException(GeneralErrorCode.EMAIL_DUPLICATE);
        }
    }

    private void validatePasswordStrength(String password) {
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new GeneralException(GeneralErrorCode.PASSWORD_TOO_WEAK);
        }
    }

    private void validatePasswordMatch(String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            throw new GeneralException(GeneralErrorCode.PASSWORD_MISMATCH);
        }
    }
}
