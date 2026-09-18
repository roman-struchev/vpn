package com.vpn.server;

import com.vpn.server.controller.ClientController;
import com.vpn.server.service.DiagnosticsService;
import com.vpn.server.service.DynamicRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientControllerTest {

    @Mock
    private DynamicRoutingService dynamicRoutingService;

    private ClientController clientController;

    @BeforeEach
    void setUp() {
        clientController = new ClientController(dynamicRoutingService, mock(DiagnosticsService.class));
    }

    @Test
    void testGetRoutingConfig() {
        DynamicRoutingService.RoutingConfigResponse resp = new DynamicRoutingService.RoutingConfigResponse(
                "XHTTP", "GRPC", "firefox", 15, 3, List.of()
        );

        when(dynamicRoutingService.getRoutingConfig("MTS", "RU-MOW")).thenReturn(resp);

        ResponseEntity<DynamicRoutingService.RoutingConfigResponse> result =
                clientController.getRoutingConfig("MTS", "RU-MOW");

        assertEquals(200, result.getStatusCode().value());
        assertEquals("XHTTP", result.getBody().primaryTransport());
    }

    @Test
    void testSubmitTelemetry() {
        Map<String, Object> req = Map.of(
                "nodeId", 1L,
                "operator", "MTS",
                "region", "RU-MOW",
                "transport", "XHTTP",
                "connectTimeMs", 120,
                "failureCount", 0,
                "isWhitelistSuspected", false
        );

        ResponseEntity<?> result = clientController.submitTelemetry(req);
        assertEquals(200, result.getStatusCode().value());
        verify(dynamicRoutingService).recordTelemetry(eq(1L), eq("MTS"), eq("RU-MOW"), eq("XHTTP"), eq(120), eq(0), eq(false));
    }
}
