package com.watermonitor.compaction;

import com.watermonitor.alert.AlertSettings;
import com.watermonitor.alert.AlertSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily retention job (owner-confirmed): raw measurements older than compaction_days
 * are rolled up into measurement_hourly, then deleted. Chart queries read both tables
 * transparently, so day-granularity history stays available forever at hourly resolution.
 */
@Component
public class CompactionJob {

    private static final Logger log = LoggerFactory.getLogger(CompactionJob.class);

    private final JdbcTemplate jdbc;
    private final AlertSettingsService settingsService;

    public CompactionJob(JdbcTemplate jdbc, AlertSettingsService settingsService) {
        this.jdbc = jdbc;
        this.settingsService = settingsService;
    }

    @Scheduled(cron = "0 0 3 * * *") // daily at 03:00 UTC
    @Transactional
    public void run() {
        AlertSettings s = settingsService.get();
        if (!s.isCompactionEnabled()) { log.info("Compaction disabled, skipping"); return; }
        int days = s.getCompactionDays();

        int inserted = jdbc.update("""
                INSERT INTO measurement_hourly
                    (device_id, hour_start, avg_pressure_psi, min_pressure_psi, max_pressure_psi, sample_count)
                SELECT device_id, date_trunc('hour', measured_at),
                       ROUND(AVG(pressure_psi), 2), MIN(pressure_psi), MAX(pressure_psi), COUNT(*)
                FROM measurement
                WHERE measured_at < now() - make_interval(days => ?)
                GROUP BY device_id, date_trunc('hour', measured_at)
                ON CONFLICT (device_id, hour_start) DO NOTHING
                """, days);

        int deleted = jdbc.update(
                "DELETE FROM measurement WHERE measured_at < now() - make_interval(days => ?)", days);

        log.info("Compaction done: {} hourly rows written, {} raw rows deleted (cutoff {} days)",
                inserted, deleted, days);
    }
}
