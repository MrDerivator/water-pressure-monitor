package com.watermonitor.tanklevel;

import com.watermonitor.alert.AlertSettingsService;
import com.watermonitor.config.SecurityFilter;
import com.watermonitor.device.Device;
import com.watermonitor.device.DeviceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class TankLevelController {

    private final TankLevelRepository tankLevels;
    private final DeviceRepository devices;
    private final AlertSettingsService settings;

    public TankLevelController(TankLevelRepository tankLevels, DeviceRepository devices, AlertSettingsService settings) {
        this.tankLevels = tankLevels;
        this.devices = devices;
        this.settings = settings;
    }

    public record IngestRequest(@NotNull BigDecimal levelPercent, BigDecimal distanceCm, OffsetDateTime measuredAt) {
    }

    public record StatusResponse(Long deviceId, String deviceName, BigDecimal levelPercent,
                                 BigDecimal distanceCm, OffsetDateTime lastMeasuredAt, boolean online) {
    }

    @PostMapping("/water-level")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest body, HttpServletRequest req) {
        Long deviceId = (Long) req.getAttribute(SecurityFilter.DEVICE_ID_ATTR);
        OffsetDateTime at = body.measuredAt() != null ? body.measuredAt() : OffsetDateTime.now(ZoneOffset.UTC);
        TankLevel saved = tankLevels.save(new TankLevel(deviceId, body.levelPercent(), body.distanceCm(), at));
        return Map.of("id", saved.getId(), "deviceId", deviceId);
    }

    @GetMapping("/water-level")
    public StatusResponse status() {
        Optional<Device> device = devices.findByDeviceType("TANK_LEVEL").stream().findFirst();
        if (device.isEmpty()) {
            return new StatusResponse(null, null, null, null, null, false);
        }
        Optional<TankLevel> last = tankLevels.findTopByDeviceIdOrderByMeasuredAtDesc(device.get().getId());
        int timeoutMin = settings.get().getOfflineTimeoutMinutes();
        boolean online = last.isPresent() &&
                Duration.between(last.get().getMeasuredAt(), OffsetDateTime.now(ZoneOffset.UTC))
                        .compareTo(Duration.ofMinutes(timeoutMin)) < 0;
        return new StatusResponse(device.get().getId(), device.get().getName(),
                last.map(TankLevel::getLevelPercent).orElse(null),
                last.map(TankLevel::getDistanceCm).orElse(null),
                last.map(TankLevel::getMeasuredAt).orElse(null), online);
    }
}