package com.vpn.server.controller;

import com.vpn.server.service.SubscriptionExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/subscription")
public class SubscriptionController {

    private final SubscriptionExportService exportService;

    public SubscriptionController(SubscriptionExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping(value = "/export/{userId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportSubscription(@PathVariable Long userId) {
        try {
            String encodedLinks = exportService.exportVlessSubscription(userId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"subscription.txt\"")
                    .header("Subscription-Userinfo", "upload=0; download=0; total=107374182400; expire=0")
                    .body(encodedLinks);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}
