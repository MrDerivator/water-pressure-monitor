package com.watermonitor.alert;

import com.watermonitor.measurement.MeasurementRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Rules (all configurable in settings):
 *  1. LOW_PRESSURE  — pressure below threshold (default 20 PSI). Resolves with +2 PSI hysteresis.
 *  2. SUDDEN_DROP   — drop > delta within window (default >15 PSI in 10 min). Resolves when the
 *                     gap to the recent max shrinks below half the delta.
 *  3. OFFLINE       — opened by the watchdog; resolved here when data arrives again.
 *  Cooldown: a type re-fires only if its last event is older than cooldown_minutes.
 */
@Service
public class AlertEngine {

    static final BigDecimal RESOLVE_HYSTERESIS_PSI = new BigDecimal("2");

    private final AlertSettingsService settingsService;
    private final AlertEventRepository events;
    private final MeasurementRepository measurements;
    private final TelegramService telegram;

    public AlertEngine(AlertSettingsService settingsService, AlertEventRepository events,
                       @Lazy MeasurementRepository measurements, TelegramService telegram) {
        this.settingsService = settingsService;
        this.events = events;
        this.measurements = measurements;
        this.telegram = telegram;
    }

    @Transactional
    public void evaluate(Long deviceId, BigDecimal pressure, OffsetDateTime at) {
        AlertSettings s = settingsService.get();

        resolveIfOpen(deviceId, AlertEvent.Type.OFFLINE, at,
                "✅ Sensor back online. Current pressure: " + pressure + " PSI");

        checkLowPressure(deviceId, pressure, at, s);
        checkSuddenDrop(deviceId, pressure, at, s);
    }

    private void checkLowPressure(Long deviceId, BigDecimal pressure, OffsetDateTime at, AlertSettings s) {
        if (pressure.compareTo(s.getLowThresholdPsi()) < 0) {
            open(deviceId, AlertEvent.Type.LOW_PRESSURE, at, s,
                    "🚨 LOW PRESSURE: " + pressure + " PSI (threshold " + s.getLowThresholdPsi()
                            + " PSI). Possible burst pipe or major leak.");
        } else if (pressure.compareTo(s.getLowThresholdPsi().add(RESOLVE_HYSTERESIS_PSI)) >= 0) {
            resolveIfOpen(deviceId, AlertEvent.Type.LOW_PRESSURE, at,
                    "✅ Pressure recovered: " + pressure + " PSI");
        }
    }

    private void checkSuddenDrop(Long deviceId, BigDecimal pressure, OffsetDateTime at, AlertSettings s) {
        BigDecimal recentMax = measurements.maxPressureSince(deviceId, at.minusMinutes(s.getDropWindowMinutes()));
        if (recentMax == null) return;
        BigDecimal drop = recentMax.subtract(pressure);

        if (drop.compareTo(s.getDropDeltaPsi()) >= 0) {
            open(deviceId, AlertEvent.Type.SUDDEN_DROP, at, s,
                    "🚨 SUDDEN PRESSURE DROP: " + recentMax + " → " + pressure + " PSI within "
                            + s.getDropWindowMinutes() + " min. Possible leak starting.");
        } else if (drop.compareTo(s.getDropDeltaPsi().divide(BigDecimal.TWO)) < 0) {
            resolveIfOpen(deviceId, AlertEvent.Type.SUDDEN_DROP, at,
                    "✅ Pressure stabilized at " + pressure + " PSI");
        }
    }

    /** Used by the offline watchdog. */
    @Transactional
    public void fireOffline(Long deviceId, OffsetDateTime lastSeen, OffsetDateTime now, AlertSettings s) {
        open(deviceId, AlertEvent.Type.OFFLINE, now, s,
                "⚠️ Sensor OFFLINE: no data since " + lastSeen + " (timeout "
                        + s.getOfflineTimeoutMinutes() + " min). Check sensor power/WiFi.");
    }

    public boolean hasOpenEvent(Long deviceId, AlertEvent.Type type) {
        return events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(deviceId, type).isPresent();
    }

    private void open(Long deviceId, AlertEvent.Type type, OffsetDateTime at, AlertSettings s, String message) {
        if (hasOpenEvent(deviceId, type)) return; // one alert per incident
        Optional<AlertEvent> last = events.findTopByDeviceIdAndTypeOrderByFiredAtDesc(deviceId, type);
        boolean inCooldown = last.isPresent() &&
                Duration.between(last.get().getFiredAt(), at).compareTo(Duration.ofMinutes(s.getCooldownMinutes())) < 0;
        if (inCooldown) return;
        events.save(new AlertEvent(deviceId, type, message, at));
        telegram.send(message);
    }

    private void resolveIfOpen(Long deviceId, AlertEvent.Type type, OffsetDateTime at, String message) {
        events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(deviceId, type).ifPresent(e -> {
            e.setResolvedAt(at);
            events.save(e);
            telegram.send(message);
        });
    }
}
