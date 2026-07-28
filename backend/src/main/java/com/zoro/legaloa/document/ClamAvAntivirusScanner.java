package com.zoro.legaloa.document;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.document.scanner.mode", havingValue = "clamav")
public class ClamAvAntivirusScanner implements AntivirusScanner {
    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvAntivirusScanner(
            @Value("${app.document.scanner.host}") String host,
            @Value("${app.document.scanner.port:3310}") int port,
            @Value("${app.document.scanner.timeout:30s}") java.time.Duration timeout
    ) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = Math.toIntExact(timeout.toMillis());
    }

    @Override
    public ScanResult scan(InputStream content, ScanMetadata metadata) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                output.writeInt(read);
                output.write(buffer, 0, read);
            }
            output.writeInt(0);
            output.flush();

            var response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) > 0) {
                response.write(value);
            }
            String result = response.toString(StandardCharsets.UTF_8).trim();
            if (result.endsWith("OK")) {
                return ScanResult.clean();
            }
            if (result.contains("FOUND")) {
                String signature = result
                        .replace("stream:", "")
                        .replace("FOUND", "")
                        .trim();
                return ScanResult.infected(signature);
            }
            return ScanResult.failed("Unexpected ClamAV response");
        }
    }

    @Override
    public String provider() {
        return "clamav";
    }

    @Override
    public boolean isHealthy() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.min(timeoutMillis, 3000));
            socket.setSoTimeout(Math.min(timeoutMillis, 3000));
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] response = socket.getInputStream().readNBytes(4);
            return "PONG".equals(new String(response, StandardCharsets.US_ASCII));
        } catch (IOException exception) {
            return false;
        }
    }
}
