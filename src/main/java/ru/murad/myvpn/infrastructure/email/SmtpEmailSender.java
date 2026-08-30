package ru.murad.myvpn.infrastructure.email;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.application.auth.EmailSender;
import ru.murad.myvpn.config.AuthProperties;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    @Override
    public void sendOtp(String recipient, String code, Duration ttl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.emailFrom());
        message.setTo(recipient);
        message.setSubject("MyVPN verification code");
        message.setText("Your MyVPN verification code is " + code
                + ". It expires in " + ttl.toMinutes() + " minutes.");
        mailSender.send(message);
    }
}
