package com.zoro.legaloa.config;

import com.zoro.legaloa.document.ObjectStorageService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("objectStorage")
public class StorageHealthIndicator implements HealthIndicator {
    private final ObjectStorageService storageService;

    public StorageHealthIndicator(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public Health health() {
        return storageService.isHealthy()
                ? Health.up().withDetail("privateBucket", "available").build()
                : Health.down().withDetail("privateBucket", "unavailable").build();
    }
}
