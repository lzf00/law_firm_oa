package com.zoro.legaloa.config;

import com.zoro.legaloa.document.AntivirusScanner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("documentScanner")
public class ScannerHealthIndicator implements HealthIndicator {
    private final AntivirusScanner scanner;

    public ScannerHealthIndicator(AntivirusScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public Health health() {
        return scanner.isHealthy()
                ? Health.up().withDetail("provider", scanner.provider()).build()
                : Health.down().withDetail("provider", scanner.provider()).build();
    }
}
