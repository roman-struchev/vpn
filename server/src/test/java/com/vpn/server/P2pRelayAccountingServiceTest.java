package com.vpn.server;

import com.vpn.server.entity.Node;
import com.vpn.server.entity.P2pRelayCredit;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.service.P2pRelayAccountingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The dual self-report cross-check is the actual fraud-resistance mechanism
 * for P2P relay credit (docs/research/P2P_RELAY_FEASIBILITY.md §8.2) — the
 * server never sees the real bytes, so this logic (not a DB constraint or a
 * network-level check) is what stands between "one lying report" and a
 * bogus credit.
 */
class P2pRelayAccountingServiceTest {

    private NodeRepository nodeRepository;
    private SubscriptionRepository subscriptionRepository;
    private P2pRelayCreditRepository creditRepository;
    private P2pRelayAccountingService service;

    private Node node;
    private User owner;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(NodeRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        creditRepository = mock(P2pRelayCreditRepository.class);
        service = new P2pRelayAccountingService(nodeRepository, subscriptionRepository, creditRepository);

        owner = new User();
        owner.setId(77L);

        node = new Node();
        node.setId(5L);
        node.setType("p2p");
        node.setOwnerUser(owner);

        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));
        when(creditRepository.findBySessionId(anyString())).thenReturn(Optional.empty());
        when(creditRepository.sumBytesCreditedSince(eq(77L), any(Instant.class))).thenReturn(0L);

        Subscription sub = new Subscription();
        sub.setTariff(tariff("pro"));
        sub.setTrafficLimitBytes(100_000_000_000L);
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(77L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Tariff tariff(String id) {
        Tariff t = new Tariff();
        t.setId(id);
        return t;
    }

    @Test
    void credits_whenBothReportsMatchExactly() {
        service.recordRelayNodeReport(5L, "sess-1", 1_000_000_000L);
        service.recordClientReport(5L, "sess-1", 1_000_000_000L);

        ArgumentCaptor<P2pRelayCredit> captor = ArgumentCaptor.forClass(P2pRelayCredit.class);
        verify(creditRepository).save(captor.capture());
        assertEquals(1_000_000_000L, captor.getValue().getBytesRelayed());
        assertEquals(500_000_000L, captor.getValue().getBytesCredited()); // 0.5 GB per 1GB relayed
    }

    @Test
    void credits_whenReportsAgreeWithinTolerance() {
        // 3% apart — within the ±5% tolerance.
        service.recordRelayNodeReport(5L, "sess-2", 1_000_000_000L);
        service.recordClientReport(5L, "sess-2", 1_030_000_000L);

        verify(creditRepository).save(any(P2pRelayCredit.class));
    }

    @Test
    void doesNotCredit_whenReportsDisagreeBeyondTolerance() {
        // 20% apart — well outside tolerance, e.g. one side padding its report.
        service.recordRelayNodeReport(5L, "sess-3", 1_000_000_000L);
        service.recordClientReport(5L, "sess-3", 1_200_000_000L);

        verify(creditRepository, never()).save(any(P2pRelayCredit.class));
    }

    @Test
    void credits_offTheSmallerReport_neverTheLarger() {
        // Even within tolerance, crediting off the larger number would let an
        // over-reporting side inflate its own payout — must use the smaller.
        service.recordRelayNodeReport(5L, "sess-4", 1_000_000_000L);
        service.recordClientReport(5L, "sess-4", 1_040_000_000L);

        ArgumentCaptor<P2pRelayCredit> captor = ArgumentCaptor.forClass(P2pRelayCredit.class);
        verify(creditRepository).save(captor.capture());
        assertEquals(1_000_000_000L, captor.getValue().getBytesRelayed());
    }

    @Test
    void doesNotCredit_untilBothSidesHaveReported() {
        service.recordRelayNodeReport(5L, "sess-5", 1_000_000_000L);
        verify(creditRepository, never()).save(any(P2pRelayCredit.class));

        service.recordClientReport(5L, "sess-5", 1_000_000_000L);
        verify(creditRepository).save(any(P2pRelayCredit.class));
    }

    @Test
    void doesNotCredit_whenNodeHasNoOwner() {
        node.setOwnerUser(null);

        service.recordRelayNodeReport(5L, "sess-6", 1_000_000_000L);
        service.recordClientReport(5L, "sess-6", 1_000_000_000L);

        verify(creditRepository, never()).save(any(P2pRelayCredit.class));
    }

    @Test
    void doesNotCredit_whenNodeIdsMismatchBetweenReports() {
        Node otherNode = new Node();
        otherNode.setId(9L);
        otherNode.setOwnerUser(owner);
        when(nodeRepository.findById(9L)).thenReturn(Optional.of(otherNode));

        service.recordRelayNodeReport(5L, "sess-7", 1_000_000_000L);
        service.recordClientReport(9L, "sess-7", 1_000_000_000L);

        verify(creditRepository, never()).save(any(P2pRelayCredit.class));
    }

    @Test
    void enforcesDailyCap_partialCreditWhenNearCap() {
        // Only 300MB of headroom left today; the natural 500MB credit (from
        // 1GB relayed at the 0.5 rate) must be capped down to that headroom.
        long remainingHeadroom = 300_000_000L;
        long alreadyCredited = P2pRelayAccountingService.DAILY_CAP_BYTES - remainingHeadroom;
        when(creditRepository.sumBytesCreditedSince(eq(77L), any(Instant.class))).thenReturn(alreadyCredited);

        service.recordRelayNodeReport(5L, "sess-8", 1_000_000_000L); // would naturally credit 500,000,000
        service.recordClientReport(5L, "sess-8", 1_000_000_000L);

        ArgumentCaptor<P2pRelayCredit> captor = ArgumentCaptor.forClass(P2pRelayCredit.class);
        verify(creditRepository).save(captor.capture());
        assertEquals(remainingHeadroom, captor.getValue().getBytesCredited());
    }

    @Test
    void doesNotCredit_whenAlreadyAtDailyCap() {
        when(creditRepository.sumBytesCreditedSince(eq(77L), any(Instant.class)))
                .thenReturn(P2pRelayAccountingService.DAILY_CAP_BYTES);

        service.recordRelayNodeReport(5L, "sess-9", 1_000_000_000L);
        service.recordClientReport(5L, "sess-9", 1_000_000_000L);

        verify(creditRepository, never()).save(any(P2pRelayCredit.class));
    }

    @Test
    void doesNotDoubleCredit_ifSessionAlreadyCredited() {
        when(creditRepository.findBySessionId("sess-10")).thenReturn(Optional.of(new P2pRelayCredit()));

        service.recordRelayNodeReport(5L, "sess-10", 1_000_000_000L);
        service.recordClientReport(5L, "sess-10", 1_000_000_000L);

        verify(creditRepository, never()).save(any(P2pRelayCredit.class));
    }

    @Test
    void bumpsOwnerSubscriptionTrafficLimit_onCredit() {
        service.recordRelayNodeReport(5L, "sess-11", 2_000_000_000L);
        service.recordClientReport(5L, "sess-11", 2_000_000_000L);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertEquals(100_000_000_000L + 1_000_000_000L, captor.getValue().getTrafficLimitBytes());
    }

}
