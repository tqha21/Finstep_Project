package com.finstep.identity;

public interface MailGateway {
    void sendToken(String recipient, String purpose, String token);
}
