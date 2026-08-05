package com.watermonitor.device;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceRepository devices;

    public DeviceController(DeviceRepository devices) { this.devices = devices; }

    public record DeviceDto(Long id, String name) {}

    @GetMapping
    public List<DeviceDto> list() {
        return devices.findAll().stream().map(d -> new DeviceDto(d.getId(), d.getName())).toList();
    }
}
