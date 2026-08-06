package com.watermonitor.measurement;

import com.watermonitor.alert.AlertEngine;
import com.watermonitor.alert.AlertSettingsService;
import com.watermonitor.device.Device;
import com.watermonitor.device.DeviceRepository;
import com.watermonitor.measurement.MeasurementDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final DeviceRepository devices;
    private final AlertEngine alertEngine;
    private final AlertSettingsService settings;

    public MeasurementService(MeasurementRepository measurements, DeviceRepository devices,
                              AlertEngine alertEngine, AlertSettingsService settings) {
        this.measurements = measurements;
        this.devices = devices;
        this.alertEngine = alertEngine;
        this.settings = settings;
    }

    @Transactional
    public Measurement ingest(Long deviceId, IngestRequest req) {
        OffsetDateTime at = req.measuredAt() != null ? req.measuredAt() : OffsetDateTime.now(ZoneOffset.UTC);
        Measurement saved = measurements.save(new Measurement(deviceId, req.pressurePsi(), at));
        alertEngine.evaluate(deviceId, req.pressurePsi(), at);
        return saved;
    }

    @Transactional(readOnly = true)
    public SeriesResponse series(Long deviceId, String granularity, OffsetDateTime from, OffsetDateTime to) {
        Map<OffsetDateTime, Bucket> merged = new TreeMap<>();

        if ("day".equals(granularity)) {
            // Every individual 2-min reading, plus any already-compacted hours for older data.
            for (Object[] row : measurements.aggregateHourly(deviceId, "hour", from, to)) put(merged, row, true);
            for (Object[] row : measurements.rawPoints(deviceId, from, to)) put(merged, row, false);
            return new SeriesResponse(granularity, new ArrayList<>(merged.values()));
        }

        String unit = switch (granularity) {
            case "month" -> "day";   // month view -> daily points
            case "year" -> "month";  // year view -> monthly points
            default -> throw new IllegalArgumentException("granularity must be day|month|year");
        };
        for (Object[] row : measurements.aggregateHourly(deviceId, unit, from, to)) put(merged, row, true);
        for (Object[] row : measurements.aggregateRaw(deviceId, unit, from, to)) put(merged, row, false);
        return new SeriesResponse(granularity, new ArrayList<>(merged.values()));
    }

    /** Raw rows win over compacted rows when both exist in a bucket boundary period. */
    private void put(Map<OffsetDateTime, Bucket> map, Object[] row, boolean compacted) {
        OffsetDateTime bucket = toOffset(row[0]);
        BigDecimal avg = scale(row[1]);
        BigDecimal min = scale(row[2]);
        BigDecimal max = scale(row[3]);
        long samples = ((Number) row[4]).longValue();
        Bucket existing = map.get(bucket);
        if (existing == null) {
            map.put(bucket, new Bucket(bucket, avg, min, max, samples));
        } else {
            // merge raw + compacted in the same bucket (weighted)
            long total = existing.samples() + samples;
            BigDecimal wAvg = existing.avg().multiply(BigDecimal.valueOf(existing.samples()))
                    .add(avg.multiply(BigDecimal.valueOf(samples)))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            map.put(bucket, new Bucket(bucket, wAvg,
                    existing.min().min(min), existing.max().max(max), total));
        }
    }

    @Transactional(readOnly = true)
    public StatusResponse status(Long deviceId) {
        Device device = devices.findById(deviceId).orElseThrow();
        Optional<Measurement> last = measurements.findTopByDeviceIdOrderByMeasuredAtDesc(deviceId);
        int timeoutMin = settings.get().getOfflineTimeoutMinutes();
        boolean online = last.isPresent() &&
                Duration.between(last.get().getMeasuredAt(), OffsetDateTime.now(ZoneOffset.UTC))
                        .compareTo(Duration.ofMinutes(timeoutMin)) < 0;
        return new StatusResponse(deviceId, device.getName(),
                last.map(Measurement::getPressurePsi).orElse(null),
                last.map(Measurement::getMeasuredAt).orElse(null), online);
    }

    private static OffsetDateTime toOffset(Object o) {
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof Instant i) return i.atOffset(ZoneOffset.UTC);
        if (o instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected bucket type: " + o.getClass());
    }

    private static BigDecimal scale(Object o) {
        return o == null ? null : new BigDecimal(o.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}
