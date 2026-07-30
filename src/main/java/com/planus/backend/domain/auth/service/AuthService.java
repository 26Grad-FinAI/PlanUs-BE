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

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

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
        savedUser.updateRefreshToken(refreshToken);

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
