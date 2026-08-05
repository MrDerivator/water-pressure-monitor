package com.watermonitor.alert;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final AlertSettingsService service;
    private final AlertEventRepository events;

    public SettingsController(AlertSettingsService service, AlertEventRepository events) {
        this.service = service;
        this.events = events;
    }

    public record SettingsDto(BigDecimal lowThresholdPsi, BigDecimal dropDeltaPsi, int dropWindowMinutes,
                              int offlineTimeoutMinutes, int cooldownMinutes, boolean telegramEnabled,
                              String telegramBotToken, String telegramChatId, String displayUnit,
                              boolean compactionEnabled, int compactionDays) {

        static SettingsDto from(AlertSettings s, boolean maskToken) {
            return new SettingsDto(s.getLowThresholdPsi(), s.getDropDeltaPsi(), s.getDropWindowMinutes(),
                    s.getOfflineTimeoutMinutes(), s.getCooldownMinutes(), s.isTelegramEnabled(),
                    maskToken && s.getTelegramBotToken() != null ? "********" : s.getTelegramBotToken(),
                    s.getTelegramChatId(), s.getDisplayUnit(), s.isCompactionEnabled(), s.getCompactionDays());
        }
    }

    @GetMapping("/settings")
    public SettingsDto get() {
        return SettingsDto.from(service.get(), true);
    }

    @PutMapping("/settings")
    public SettingsDto update(@RequestBody SettingsDto dto) {
        AlertSettings s = service.get();
        s.setLowThresholdPsi(dto.lowThresholdPsi());
        s.setDropDeltaPsi(dto.dropDeltaPsi());
        s.setDropWindowMinutes(dto.dropWindowMinutes());
        s.setOfflineTimeoutMinutes(dto.offlineTimeoutMinutes());
        s.setCooldownMinutes(dto.cooldownMinutes());
        s.setTelegramEnabled(dto.telegramEnabled());
        if (dto.telegramBotToken() != null && !dto.telegramBotToken().isBlank()
                && !"********".equals(dto.telegramBotToken())) {
            s.setTelegramBotToken(dto.telegramBotToken()); // masked value means "unchanged"
        }
        s.setTelegramChatId(dto.telegramChatId());
        s.setDisplayUnit(dto.displayUnit());
        s.setCompactionEnabled(dto.compactionEnabled());
        s.setCompactionDays(dto.compactionDays());
        return SettingsDto.from(service.save(s), true);
    }

    public record AlertEventDto(Long id, Long deviceId, String type, String message,
                                String firedAt, String resolvedAt) {}

    @GetMapping("/alerts")
    public List<AlertEventDto> recentAlerts() {
        return events.findTop20ByOrderByFiredAtDesc().stream()
                .map(e -> new AlertEventDto(e.getId(), e.getDeviceId(), e.getType().name(), e.getMessage(),
                        e.getFiredAt().toString(),
                        e.getResolvedAt() != null ? e.getResolvedAt().toString() : null))
                .toList();
    }
}
