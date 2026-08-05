package com.planus.backend.domain.user;

import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 신규 사용자 저장 전담 컴포넌트.
 *
 * <p>REQUIRES_NEW 전파 속성으로 독립된 트랜잭션에서 실행된다.
 * 중복 키 예외 발생 시 이 트랜잭션만 롤백되고 상위 트랜잭션은 유지된다.</p>
 */
@Component
@RequiredArgsConstructor
public class UserAccountPersister {

    private final UserAccountRepository userAccountRepository;

    /**
     * 사용자 계정을 새 트랜잭션에서 저장하고 즉시 플러시한다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 중복 키 위반 시
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccount saveAndFlush(UserAccount userAccount) {
        return userAccountRepository.saveAndFlush(userAccount);
    }
}
