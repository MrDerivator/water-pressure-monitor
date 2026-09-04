package com.watermonitor.config;

import com.watermonitor.alert.AlertSettingsService;
import com.watermonitor.device.Device;
import com.watermonitor.device.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @Bean
    CommandLineRunner seed(DeviceRepository devices, AlertSettingsService settings, AppProperties props) {
        return args -> {
            if (devices.count() == 0) {
                Device d = new Device();
                d.setName(props.deviceName());
                d.setApiKey(props.deviceApiKey());
                d.setDeviceType("PRESSURE");
                devices.save(d);
                log.info("Bootstrap: registered initial pressure device '{}'", props.deviceName());
            }
            if (props.tankDeviceApiKey() != null && !props.tankDeviceApiKey().isBlank()
                    && devices.findByDeviceType("TANK_LEVEL").isEmpty()) {
                Device tank = new Device();
                tank.setName(props.tankDeviceName());
                tank.setApiKey(props.tankDeviceApiKey());
                tank.setDeviceType("TANK_LEVEL");
                devices.save(tank);
                log.info("Bootstrap: registered tank level device '{}'", props.tankDeviceName());
            }
            settings.seedIfEmpty(props);
        };
    }
}