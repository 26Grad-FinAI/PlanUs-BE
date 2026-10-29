package com.planus.backend.global.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 이메일 발송 서비스. */
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    /** 비밀번호 재설정 인증코드를 이메일로 발송한다. */
    public void sendVerificationCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("[Planus] 비밀번호 재설정 인증코드");
        message.setText("비밀번호 재설정 인증코드: " + code + "\n\n이 코드는 10분간 유효합니다.");
        mailSender.send(message);
    }
}
