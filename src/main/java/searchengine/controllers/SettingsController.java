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

    @GetMapping("/timeout")
    public Map<String, Integer> getTimeout() {
        return Map.of("timeout", connectionSettings.getTimeout());
    }

    @PostMapping("/timeout")
    public ResponseEntity<Map<String, Integer>> setTimeout(@RequestBody Map<String, Integer> body) {
        Integer value = body.get("timeout");
        if (value == null || value < 0) {
            return ResponseEntity.badRequest().build();
        }
        connectionSettings.setTimeout(value);
        return ResponseEntity.ok(Map.of("timeout", connectionSettings.getTimeout()));
    }
}