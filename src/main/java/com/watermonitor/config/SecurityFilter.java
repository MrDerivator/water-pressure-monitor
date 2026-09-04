package com.watermonitor.config;

import com.watermonitor.device.DeviceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Two auth schemes:
 *  - POST /api/measurements: X-Api-Key header must match a registered device (ESP32 ingestion)
 *  - all other /api/**: dashboard session (set by POST /api/login), except /api/login and /api/health
 *
 * Two dashboard roles:
 *  - ADMIN  : full access, including PUT /api/settings
 *  - VIEWER : read-only -- PUT /api/settings is blocked here, server-side, regardless of the UI
 */
@Component
public class SecurityFilter extends OncePerRequestFilter {

    public static final String DEVICE_ID_ATTR = "authenticatedDeviceId";
    public static final String SESSION_AUTH = "authed";
    public static final String SESSION_ROLE = "role";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_VIEWER = "VIEWER";

    private final DeviceRepository devices;

    public SecurityFilter(DeviceRepository devices) { this.devices = devices; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();

        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }
        if (path.equals("/api/login") || path.equals("/api/health")) { chain.doFilter(req, res); return; }

        if ((path.equals("/api/measurements") || path.equals("/api/water-level")) && "POST".equalsIgnoreCase(req.getMethod())) {
            String key = req.getHeader("X-Api-Key");
            if (key == null || key.isBlank()) { unauthorized(res, "Missing X-Api-Key"); return; }
            var device = devices.findByApiKey(key);
            if (device.isEmpty()) { unauthorized(res, "Invalid API key"); return; }
            req.setAttribute(DEVICE_ID_ATTR, device.get().getId());
            chain.doFilter(req, res);
            return;
        }

        HttpSession session = req.getSession(false);
        boolean authed = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_AUTH));
        if (!authed) { unauthorized(res, "Login required"); return; }

        String role = (String) session.getAttribute(SESSION_ROLE);
        boolean isSettingsWrite = path.equals("/api/settings") && "PUT".equalsIgnoreCase(req.getMethod());
        if (isSettingsWrite && !ROLE_ADMIN.equals(role)) { forbidden(res, "Admin access required"); return; }

        chain.doFilter(req, res);
    }

    private void unauthorized(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    private void forbidden(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}