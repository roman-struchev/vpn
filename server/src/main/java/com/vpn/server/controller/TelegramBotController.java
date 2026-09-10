package com.vpn.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.vpn.server.service.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramBotController {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotController.class);

    private final TelegramBotService telegramBotService;

    @Value("${vpn.telegram.webhook-secret:}")
    private String webhookSecret;

    public TelegramBotController(TelegramBotService telegramBotService) {
        this.telegramBotService = telegramBotService;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody JsonNode update
    ) {
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (!webhookSecret.equals(secretToken)) {
                log.warn("Unauthorized webhook request: secret token mismatch");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        try {
            telegramBotService.processUpdate(update);
        } catch (Exception e) {
            log.error("Error processing Telegram update: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}
