package com.vpn.server.dto;

import com.vpn.server.entity.BalanceEntry;

import java.time.Instant;

/**
 * One row of a user's own balance-ledger history (GET /api/v1/user/balance-history)
 * -- deposits, subscription debits, referral bonuses, refunds, manual adjustments.
 * Deliberately a standalone shape rather than serializing BalanceEntry directly:
 * BalanceEntry.user is a LAZY @ManyToOne that's @JsonIgnore'd for good reason (see
 * the comment on that field) but a hand-picked DTO is the safer long-term contract
 * for a client-facing endpoint regardless.
 */
public record BalanceHistoryEntryResponse(
        Long id,
        String type,
        Long amountUsdtMicro,
        Long balanceAfterMicro,
        String description,
        Instant createdAt
) {
    public static BalanceHistoryEntryResponse from(BalanceEntry entry) {
        return new BalanceHistoryEntryResponse(
                entry.getId(),
                entry.getType(),
                entry.getAmountUsdtMicro(),
                entry.getBalanceAfterMicro(),
                entry.getDescription(),
                entry.getCreatedAt()
        );
    }
}
