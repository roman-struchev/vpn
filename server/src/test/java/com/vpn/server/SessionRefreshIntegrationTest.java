package com.vpn.server;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DeviceAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two server halves of "the app stops working after 30 days": a missing
 * or dead token has to come back as 401 (not Spring's default 403, which
 * clients cannot tell from a real refusal), and a live token has to be
 * exchangeable for a fresh one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionRefreshIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DeviceAuthService deviceAuthService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingOrInvalidTokenIsAnswered401() throws Exception {
        assertEquals(401, send("GET", "/api/v1/user/profile", null).statusCode());
        HttpResponse<String> bad = send("GET", "/api/v1/user/profile", "not-a-jwt");
        assertEquals(401, bad.statusCode());
        assertTrue(bad.body().contains("Unauthorized"));
    }

    @Test
    void liveTokenIsExchangedForAFreshOne() throws Exception {
        AuthResponse session = deviceAuthService.authenticateDevice(UUID.randomUUID().toString(), null);

        HttpResponse<String> resp = send("POST", "/api/v1/auth/refresh", session.token());

        assertEquals(200, resp.statusCode(), resp.body());
        String fresh = resp.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertTrue(jwtUtil.validateToken(fresh));
        assertEquals(String.valueOf(session.userId()), jwtUtil.parseToken(fresh).getSubject());
    }

    @Test
    void refreshNeedsAToken() throws Exception {
        assertEquals(401, send("POST", "/api/v1/auth/refresh", null).statusCode());
    }

    @Test
    void blockedAccountCannotRefresh() throws Exception {
        AuthResponse session = deviceAuthService.authenticateDevice(UUID.randomUUID().toString(), null);
        User user = userRepository.findById(session.userId()).orElseThrow();
        user.setStatus("BLOCKED");
        userRepository.save(user);

        assertEquals(401, send("POST", "/api/v1/auth/refresh", session.token()).statusCode());
    }

    @Test
    void aSignedInUserRefusedByRoleStillGets403NotA401() throws Exception {
        // A 401 tells the client its session is gone; a normal account asking
        // for an admin endpoint has a perfectly good session.
        AuthResponse session = deviceAuthService.authenticateDevice(UUID.randomUUID().toString(), null);
        assertEquals(403, send("GET", "/api/v1/admin/nodes", session.token()).statusCode());
    }
}
