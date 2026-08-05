package com.watermonitor.alert;

import com.watermonitor.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AlertSettingsService.class);
    private final AlertSettingsRepository repo;

    public AlertSettingsService(AlertSettingsRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public AlertSettings get() {
        return repo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Alert settings not seeded"));
    }

    @Transactional
    public AlertSettings save(AlertSettings s) { return repo.save(s); }

    /** Env-seeded once; DB row is the editable source of truth afterwards. */
    @Transactional
    public void seedIfEmpty(AppProperties props) {
        if (repo.count() > 0) return;
        AlertSettings s = new AlertSettings();
        s.setTelegramEnabled(props.telegram().enabled());
        s.setTelegramBotToken(emptyToNull(props.telegram().botToken()));
        s.setTelegramChatId(emptyToNull(props.telegram().chatId()));
        repo.save(s);
        log.info("Bootstrap: seeded alert settings (telegramEnabled={})", s.isTelegramEnabled());
    }

    private static String emptyToNull(String v) { return v == null || v.isBlank() ? null : v; }
}
