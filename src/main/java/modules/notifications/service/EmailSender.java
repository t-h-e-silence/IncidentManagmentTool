package modules.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends one plain-text email over SMTP.
 */
@Component
public class EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailSender(JavaMailSender mailSender, @Value("${notifications.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * @throws MailException if the SMTP server is unreachable or rejects the message
     */
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
