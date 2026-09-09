package com.vpn.server.controller;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    public UserController(UserRepository userRepository, SubscriptionRepository subscriptionRepository) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();

        Optional<Subscription> sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "role", user.getRole(),
                "balanceUsdtMicro", user.getBalanceUsdtMicro(),
                "referralCode", user.getReferralCode(),
                "hasActiveSubscription", sub.isPresent(),
                "subscription", sub.map(s -> Map.of(
                        "id", s.getId(),
                        "tariffId", s.getTariff().getId(),
                        "trafficUsedBytes", s.getTrafficUsedBytes(),
                        "trafficLimitBytes", s.getTrafficLimitBytes(),
                        "expiresAt", s.getCurrentPeriodEnd().toString()
                )).orElse(Map.of())
        ));
    }
}
