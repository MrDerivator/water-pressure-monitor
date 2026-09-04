package com.watermonitor.measurement;

import com.watermonitor.config.SecurityFilter;
import com.watermonitor.device.DeviceRepository;
import com.watermonitor.measurement.MeasurementDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeasurementController {

    private final MeasurementService service;
    private final DeviceRepository devices;

    public MeasurementController(MeasurementService service, DeviceRepository devices) {
        this.service = service;
        this.devices = devices;
    }

    @PostMapping("/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest body, HttpServletRequest req) {
        Long deviceId = (Long) req.getAttribute(SecurityFilter.DEVICE_ID_ATTR);
        Measurement saved = service.ingest(deviceId, body);
        return Map.of("id", saved.getId(), "deviceId", deviceId);
    }

    @GetMapping("/measurements")
    public SeriesResponse series(@RequestParam(defaultValue = "day") String granularity,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                 @RequestParam(required = false) Long deviceId) {
        return service.series(resolveDevice(deviceId), granularity, from, to);
    }

    @GetMapping("/status")
    public StatusResponse status(@RequestParam(required = false) Long deviceId) {
        return service.status(resolveDevice(deviceId));
    }

    /**
     * Single-device convenience: when no deviceId given, use the first registered device.
     */
    private Long resolveDevice(Long deviceId) {
        if (deviceId != null) return deviceId;
        return devices.findByDeviceType("PRESSURE").stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No pressure device registered")).getId();
    }
}
