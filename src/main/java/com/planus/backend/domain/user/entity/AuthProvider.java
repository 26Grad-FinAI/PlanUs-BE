package com.planus.backend.domain.user.entity;

/** 소셜 로그인 제공자. LOCAL은 이메일/비밀번호 가입을 의미한다. */
public enum AuthProvider {
    LOCAL,
    GOOGLE,
    KAKAO
}
