package com.watermonitor.alert;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "alert_settings")
public class AlertSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "low_threshold_psi", nullable = false)
    private BigDecimal lowThresholdPsi = new BigDecimal("20");

    @Column(name = "drop_delta_psi", nullable = false)
    private BigDecimal dropDeltaPsi = new BigDecimal("15");

    @Column(name = "drop_window_minutes", nullable = false)
    private int dropWindowMinutes = 10;

    @Column(name = "offline_timeout_minutes", nullable = false)
    private int offlineTimeoutMinutes = 10;

    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes = 30;

    @Column(name = "telegram_enabled", nullable = false)
    private boolean telegramEnabled = false;

    @Column(name = "telegram_bot_token")
    private String telegramBotToken;

    @Column(name = "telegram_chat_id")
    private String telegramChatId;

    @Column(name = "display_unit", nullable = false)
    private String displayUnit = "PSI";

    @Column(name = "compaction_enabled", nullable = false)
    private boolean compactionEnabled = true;

    @Column(name = "compaction_days", nullable = false)
    private int compactionDays = 90;

    public Long getId() { return id; }
    public BigDecimal getLowThresholdPsi() { return lowThresholdPsi; }
    public void setLowThresholdPsi(BigDecimal v) { this.lowThresholdPsi = v; }
    public BigDecimal getDropDeltaPsi() { return dropDeltaPsi; }
    public void setDropDeltaPsi(BigDecimal v) { this.dropDeltaPsi = v; }
    public int getDropWindowMinutes() { return dropWindowMinutes; }
    public void setDropWindowMinutes(int v) { this.dropWindowMinutes = v; }
    public int getOfflineTimeoutMinutes() { return offlineTimeoutMinutes; }
    public void setOfflineTimeoutMinutes(int v) { this.offlineTimeoutMinutes = v; }
    public int getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }
    public boolean isTelegramEnabled() { return telegramEnabled; }
    public void setTelegramEnabled(boolean v) { this.telegramEnabled = v; }
    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String v) { this.telegramBotToken = v; }
    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String v) { this.telegramChatId = v; }
    public String getDisplayUnit() { return displayUnit; }
    public void setDisplayUnit(String v) { this.displayUnit = v; }
    public boolean isCompactionEnabled() { return compactionEnabled; }
    public void setCompactionEnabled(boolean v) { this.compactionEnabled = v; }
    public int getCompactionDays() { return compactionDays; }
    public void setCompactionDays(int v) { this.compactionDays = v; }
}
