package com.watermonitor.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private final AlertSettingsService settings;
    private final RestClient http = RestClient.create();

    public TelegramService(AlertSettingsService settings) { this.settings = settings; }

    /** Outbound-only: sendMessage via the Bot API. Never throws — alerting must not break ingestion. */
    public void send(String text) {
        AlertSettings s = settings.get();
        if (!s.isTelegramEnabled()) { log.info("Telegram disabled, skipping: {}", text); return; }
        if (s.getTelegramBotToken() == null || s.getTelegramChatId() == null) {
            log.warn("Telegram enabled but token/chatId missing, skipping: {}", text);
            return;
        }
        try {
            http.post()
                .uri("https://api.telegram.org/bot{token}/sendMessage", s.getTelegramBotToken())
                .body(Map.of("chat_id", s.getTelegramChatId(), "text", text))
                .header("Content-Type", "application/json")
                .retrieve()
                .toBodilessEntity();
            log.info("Telegram alert sent: {}", text);
        } catch (Exception e) {
            log.error("Telegram send failed: {}", e.getMessage());
        }
    }
}
