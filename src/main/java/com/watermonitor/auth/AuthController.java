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
        String submitted = body == null ? null : body.password();
        if (submitted == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Wrong password"));
        }

        String role = null;
        if (constantTimeEquals(submitted, props.dashboardPassword())) {
            role = SecurityFilter.ROLE_ADMIN;
        } else if (props.viewerPassword() != null && !props.viewerPassword().isBlank()
                && constantTimeEquals(submitted, props.viewerPassword())) {
            role = SecurityFilter.ROLE_VIEWER;
        }

        if (role == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Wrong password"));
        }

        HttpSession session = req.getSession(true);
        session.setAttribute(SecurityFilter.SESSION_AUTH, Boolean.TRUE);
        session.setAttribute(SecurityFilter.SESSION_ROLE, role);
        return ResponseEntity.ok(Map.of("ok", true, "role", role));
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        String role = session != null ? (String) session.getAttribute(SecurityFilter.SESSION_ROLE) : null;
        // sessions created before this change won't have a role yet -- default them to ADMIN
        return Map.of("authed", true, "role", role != null ? role : SecurityFilter.ROLE_ADMIN);
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "up"); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}