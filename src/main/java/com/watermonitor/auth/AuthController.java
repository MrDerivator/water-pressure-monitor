package com.watermonitor.auth;

import com.watermonitor.config.AppProperties;
import com.watermonitor.config.SecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AppProperties props;

    public AuthController(AppProperties props) { this.props = props; }

    public record LoginRequest(String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest req) {
        if (body == null || body.password() == null || !constantTimeEquals(body.password(), props.dashboardPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Wrong password"));
        }
        HttpSession session = req.getSession(true);
        session.setAttribute(SecurityFilter.SESSION_AUTH, Boolean.TRUE);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Boolean> me() {
        // reaching here means the SecurityFilter accepted the session
        return Map.of("authed", true);
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "up"); }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
