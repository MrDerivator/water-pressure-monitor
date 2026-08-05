package com.watermonitor.measurement;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class MeasurementDtos {

    public record IngestRequest(@NotNull BigDecimal pressurePsi, OffsetDateTime measuredAt) {}

    public record Bucket(OffsetDateTime bucket, BigDecimal avg, BigDecimal min, BigDecimal max, long samples) {}

    public record SeriesResponse(String granularity, List<Bucket> buckets) {}

    public record StatusResponse(Long deviceId, String deviceName, BigDecimal lastPressurePsi,
                                 OffsetDateTime lastMeasuredAt, boolean online) {}
}
