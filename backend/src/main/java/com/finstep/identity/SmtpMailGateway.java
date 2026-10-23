package com.finstep.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailGateway implements MailGateway {
    private final JavaMailSender sender;
    private final String from;

    public SmtpMailGateway(JavaMailSender sender, @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void sendToken(String recipient, String purpose, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(purpose.equals("VERIFY") ? "Finstep: Xác minh email" : "Finstep: Đặt lại mật khẩu");
        // No frontend exists yet. A future frontend will submit the token to the JSON API.
        message.setText("Mã xác nhận dùng một lần: " + token
            + "\nHết hạn sau " + (purpose.equals("VERIFY") ? "24 giờ." : "30 phút.")
            + "\nNếu bạn không yêu cầu thao tác này, hãy bỏ qua email.");
        sender.send(message);
    }
}
