package com.planus.backend.domain.auth.service;

import com.planus.backend.domain.auth.converter.AuthConverter;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입 비즈니스 로직. 입력값 검증 → 사용자 저장 → JWT 발급 순서로 처리한다. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

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
