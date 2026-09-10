package com.vpn.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.controller.TelegramBotController;
import com.vpn.server.service.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramBotControllerTest {

    @Mock
    private TelegramBotService telegramBotService;

    private TelegramBotController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new TelegramBotController(telegramBotService);
    }

    @Test
    void testWebhookWithoutSecretSucceeds() throws Exception {
        var update = objectMapper.readTree("{\"update_id\": 1}");
        ResponseEntity<Void> res = controller.handleWebhook(null, update);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(telegramBotService).processUpdate(any());
    }

    @Test
    void testWebhookWithMatchingSecretSucceeds() throws Exception {
        controller.setWebhookSecret("my_secret_token");
        var update = objectMapper.readTree("{\"update_id\": 2}");
        ResponseEntity<Void> res = controller.handleWebhook("my_secret_token", update);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(telegramBotService).processUpdate(any());
    }

    @Test
    void testWebhookWithMismatchingSecretReturnsUnauthorized() throws Exception {
        controller.setWebhookSecret("correct_secret");
        var update = objectMapper.readTree("{\"update_id\": 3}");
        ResponseEntity<Void> res = controller.handleWebhook("wrong_secret", update);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }
}
