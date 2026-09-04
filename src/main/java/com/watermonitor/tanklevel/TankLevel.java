package com.watermonitor.tanklevel;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tank_level")
public class TankLevel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "level_percent", nullable = false)
    private BigDecimal levelPercent;

    @Column(name = "distance_cm")
    private BigDecimal distanceCm;

    @Column(name = "measured_at", nullable = false)
    private OffsetDateTime measuredAt;

    public TankLevel() {
    }

    public TankLevel(Long deviceId, BigDecimal levelPercent, BigDecimal distanceCm, OffsetDateTime measuredAt) {
        this.deviceId = deviceId;
        this.levelPercent = levelPercent;
        this.distanceCm = distanceCm;
        this.measuredAt = measuredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public BigDecimal getLevelPercent() {
        return levelPercent;
    }

    public BigDecimal getDistanceCm() {
        return distanceCm;
    }

    public OffsetDateTime getMeasuredAt() {
        return measuredAt;
    }
}