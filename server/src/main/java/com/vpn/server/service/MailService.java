package com.vpn.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Plain-text email, when SMTP is configured (SPRING_MAIL_HOST & co.). Without
 * it the sender bean doesn't exist and {@link #isEnabled()} is false, so
 * callers fall back to Telegram rather than pretending a mail went out.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;

    @Value("${vpn.mail.from:}")
    private String from = "";

    public MailService(ObjectProvider<JavaMailSender> sender) {
        this.sender = sender;
    }

    public boolean isEnabled() {
        return sender.getIfAvailable() != null;
    }

    /** @return whether it was handed to the SMTP server. */
    public boolean send(String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null || to == null || to.isBlank()) {
            return false;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            if (from != null && !from.isBlank()) msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mail.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("Mail to {} failed: {}", to, e.getMessage());
            return false;
        }
    }
}
