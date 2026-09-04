package com.watermonitor.tanklevel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TankLevelRepository extends JpaRepository<TankLevel, Long> {
    Optional<TankLevel> findTopByDeviceIdOrderByMeasuredAtDesc(Long deviceId);
}