package com.vpn.server.service;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * What every client should say about a user's plan, in one place.
 *
 * A plan that ran out of traffic is EXHAUSTED, not ACTIVE, but it is still
 * the user's plan until its period ends: looking only at ACTIVE made every
 * surface say "no active subscription" to someone who had paid for this
 * month. And "no plan" had one wording for three different situations —
 * the trial's gigabyte is spent, the paid traffic is spent, the plan
 * expired — which need three different next steps.
 */
@Service
public class CurrentPlanService {

    /** Why there is no working plan; null while one works. */
    public static final String TRIAL_USED_UP = "TRIAL_USED_UP";
    public static final String TRAFFIC_USED_UP = "TRAFFIC_USED_UP";
    public static final String EXPIRED = "EXPIRED";
    public static final String NONE = "NONE";

    private final SubscriptionRepository subscriptionRepository;

    @Value("${vpn.public.web-base-url:https://vpn.struchev.site}")
    private String publicWebBaseUrl = "https://vpn.struchev.site";

    @Value("${vpn.support.telegram:struchev}")
    private String supportTelegram = "struchev";

    public CurrentPlanService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /** The working plan, else one that ran out of traffic but whose period is still running. */
    public Optional<Subscription> currentPlan(Long userId) {
        Optional<Subscription> active = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");
        if (active.isPresent()) {
            return active;
        }
        return subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "EXHAUSTED")
                .filter(s -> s.getCurrentPeriodEnd().isAfter(Instant.now()));
    }

    public String inactiveReason(Long userId, Subscription current) {
        if (current != null) {
            if ("ACTIVE".equals(current.getStatus())) {
                return null;
            }
            return isTrial(current) ? TRIAL_USED_UP : TRAFFIC_USED_UP;
        }
        if (subscriptionRepository.existsByUserIdAndStatus(userId, "EXPIRED")) {
            return EXPIRED;
        }
        if (subscriptionRepository.existsByUserIdAndTariffId(userId, "trial")) {
            return TRIAL_USED_UP;
        }
        return NONE;
    }

    /**
     * The auto-updating subscription URL for third-party clients (v2rayTun,
     * Hiddify, Happ): every current node, refreshed by the client itself.
     * A single copied vless:// key points at one node and silently dies with it.
     */
    public String subscriptionUrl(User user) {
        return webBaseUrl() + "/api/v1/subscription/export/" + user.getSubscriptionToken();
    }

    /** Where a user asks for help: the support Telegram account. */
    public String supportUrl() {
        return "https://t.me/" + supportTelegram.trim().replaceFirst("^@", "");
    }

    public String webBaseUrl() {
        String base = publicWebBaseUrl == null ? "" : publicWebBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static boolean isTrial(Subscription sub) {
        return sub.getEffectiveTariff() != null && "trial".equalsIgnoreCase(sub.getEffectiveTariff().getId());
    }
}
