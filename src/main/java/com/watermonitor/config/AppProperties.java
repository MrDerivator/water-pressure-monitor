package com.watermonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dashboardPassword,
        String viewerPassword,
        String deviceApiKey,
        String deviceName,
        String tankDeviceApiKey,
        String tankDeviceName,
        Telegram telegram
) {
    public record Telegram(boolean enabled, String botToken, String chatId) {
    }
}