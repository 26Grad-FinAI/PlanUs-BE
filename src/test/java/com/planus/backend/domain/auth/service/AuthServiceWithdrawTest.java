package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceWithdrawTest {

    private UserAccountRepository userAccountRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        authService =
                new AuthService(
                        userAccountRepository,
                        mock(PasswordEncoder.class),
                        mock(JwtProvider.class),
                        mock(EntityManager.class));
    }

    @Test
    @DisplayName("회원 탈퇴 시 해당 유저가 삭제된다")
    void withdraw_deletesUser() {
        UserAccount user = UserAccount.builder().id(1L).build();
        when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

        authService.withdraw(1L);

        verify(userAccountRepository).delete(user);
    }

    @Test
    @DisplayName("존재하지 않는 userId로 회원 탈퇴 시 NOT_FOUND 예외가 발생한다")
    void withdraw_userNotFound_throwsNotFound() {
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.withdraw(999L))
                .isInstanceOf(GeneralException.class)
                .satisfies(
                        ex ->
                                assertThat(((GeneralException) ex).getErrorCode())
                                        .isEqualTo(GeneralErrorCode.NOT_FOUND));
    }
}
