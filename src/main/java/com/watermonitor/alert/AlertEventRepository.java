package com.watermonitor.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    Optional<AlertEvent> findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(Long deviceId, AlertEvent.Type type);

    Optional<AlertEvent> findTopByDeviceIdAndTypeOrderByFiredAtDesc(Long deviceId, AlertEvent.Type type);

    List<AlertEvent> findTop20ByOrderByFiredAtDesc();
}
