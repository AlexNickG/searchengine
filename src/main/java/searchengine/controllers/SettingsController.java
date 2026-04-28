package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.config.ConnectionSettings;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ConnectionSettings connectionSettings;

    @GetMapping("/connection")
    public Map<String, Object> getConnectionSettings() {
        return Map.of(
                "timeout",   connectionSettings.getTimeout(),
                "userAgent", connectionSettings.getUserAgent(),
                "referrer",  connectionSettings.getReferrer()
        );
    }

    @PostMapping("/connection")
    public ResponseEntity<Map<String, Object>> updateConnectionSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("timeout")) {
            int timeout = (int) body.get("timeout");
            if (timeout < 0) return ResponseEntity.badRequest().build();
            connectionSettings.setTimeout(timeout);
        }
        if (body.containsKey("userAgent")) {
            String ua = (String) body.get("userAgent");
            if (ua != null && !ua.isBlank()) connectionSettings.setUserAgent(ua.trim());
        }
        if (body.containsKey("referrer")) {
            String ref = (String) body.get("referrer");
            if (ref != null) connectionSettings.setReferrer(ref.trim());
        }
        return ResponseEntity.ok(Map.of(
                "timeout",   connectionSettings.getTimeout(),
                "userAgent", connectionSettings.getUserAgent(),
                "referrer",  connectionSettings.getReferrer()
        ));
    }
}