package com.watermonitor.measurement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MeasurementRepository extends JpaRepository<Measurement, Long> {

    Optional<Measurement> findTopByDeviceIdOrderByMeasuredAtDesc(Long deviceId);

    @Query(value = """
            SELECT MAX(pressure_psi) FROM measurement
            WHERE device_id = :deviceId AND measured_at >= :since
            """, nativeQuery = true)
    BigDecimal maxPressureSince(@Param("deviceId") Long deviceId, @Param("since") OffsetDateTime since);

    /** Aggregate raw measurements into date_trunc buckets. unit: 'hour' | 'day' | 'month' */
    @Query(value = """
            SELECT date_trunc(:unit, measured_at) AS bucket,
                   AVG(pressure_psi)  AS avg_psi,
                   MIN(pressure_psi)  AS min_psi,
                   MAX(pressure_psi)  AS max_psi,
                   COUNT(*)           AS samples
            FROM measurement
            WHERE device_id = :deviceId AND measured_at >= :from AND measured_at < :to
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> aggregateRaw(@Param("deviceId") Long deviceId,
                                @Param("unit") String unit,
                                @Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to);

    /** Same aggregation over the compacted hourly table (weighted average). */
    @Query(value = """
            SELECT date_trunc(:unit, hour_start) AS bucket,
                   SUM(avg_pressure_psi * sample_count) / NULLIF(SUM(sample_count), 0) AS avg_psi,
                   MIN(min_pressure_psi) AS min_psi,
                   MAX(max_pressure_psi) AS max_psi,
                   SUM(sample_count)     AS samples
            FROM measurement_hourly
            WHERE device_id = :deviceId AND hour_start >= :from AND hour_start < :to
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> aggregateHourly(@Param("deviceId") Long deviceId,
                                   @Param("unit") String unit,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to);
}
