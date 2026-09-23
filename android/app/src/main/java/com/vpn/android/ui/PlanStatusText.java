package com.vpn.android.ui;

import android.content.Context;

import com.vpn.android.R;
import com.vpn.android.billing.PlanSummary;

import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;

/**
 * "No active subscription" used to be the one sentence for four different
 * situations that need four different next steps. Shared by the connect
 * screen and the account tab.
 */
public final class PlanStatusText {

    private PlanStatusText() {}

    /** Why nothing works right now, or null while the plan works. */
    public static String inactive(Context context, PlanSummary plan) {
        String reason = plan.inactiveReason();
        if (reason == null) return null;
        switch (reason) {
            case "TRIAL_USED_UP":
                return context.getString(R.string.plan_inactive_trial_used_up);
            case "TRAFFIC_USED_UP":
                return context.getString(R.string.plan_inactive_traffic_used_up, date(plan.refillAtIso()));
            case "EXPIRED":
                return context.getString(R.string.plan_inactive_expired);
            default:
                return context.getString(R.string.state_no_subscription);
        }
    }

    /** Traffic nearly gone, or what renewal will do — null when there's nothing to add. */
    public static String working(Context context, PlanSummary plan) {
        if (plan.isRunningOut()) {
            return context.getString(R.string.plan_low_traffic);
        }
        PlanSummary.Renewal renewal = plan.renewal();
        if (renewal == null || plan.expiresAtIso() == null) return null;
        String end = date(plan.expiresAtIso());
        if (renewal.shortfallUsdt > 0) {
            return context.getString(R.string.plan_renewal_short, end, renewal.priceUsdt, renewal.shortfallUsdt);
        }
        return renewal.nextPlanName != null
                ? context.getString(R.string.plan_renewal_into, end, renewal.nextPlanName)
                : context.getString(R.string.plan_renewal_on, end);
    }

    static String date(String iso) {
        if (iso == null) return "—";
        try {
            return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(iso)));
        } catch (Exception e) {
            return iso;
        }
    }
}
