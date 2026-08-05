package com.watermonitor.alert;

import com.watermonitor.device.DeviceRepository;
import com.watermonitor.measurement.Measurement;
import com.watermonitor.measurement.MeasurementRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Component
public class OfflineWatchdog {

    private final DeviceRepository devices;
    private final MeasurementRepository measurements;
    private final AlertEngine engine;
    private final AlertSettingsService settingsService;

    public OfflineWatchdog(DeviceRepository devices, MeasurementRepository measurements,
                           AlertEngine engine, AlertSettingsService settingsService) {
        this.devices = devices;
        this.measurements = measurements;
        this.engine = engine;
        this.settingsService = settingsService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void check() {
        AlertSettings s = settingsService.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        devices.findAll().forEach(device -> {
            Optional<Measurement> last = measurements.findTopByDeviceIdOrderByMeasuredAtDesc(device.getId());
            if (last.isEmpty()) return; // never reported yet — don't alarm
            OffsetDateTime lastSeen = last.get().getMeasuredAt();
            boolean offline = Duration.between(lastSeen, now)
                    .compareTo(Duration.ofMinutes(s.getOfflineTimeoutMinutes())) >= 0;
            if (offline && !engine.hasOpenEvent(device.getId(), AlertEvent.Type.OFFLINE)) {
                engine.fireOffline(device.getId(), lastSeen, now, s);
            }
        });
    }
}
