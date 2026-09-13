package com.vpn.server.repository;

import com.vpn.server.entity.NodeCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NodeCredentialRepository extends JpaRepository<NodeCredential, Long> {
    // Not Optional<NodeCredential>: re-registering a node (registerNode) used to
    // leave the previous credential unrevoked, so more than one row could match
    // — a singular finder here threw NonUniqueResultException and permanently
    // broke that node's gRPC sync stream (every reconnect re-authenticates).
    // registerNode now revokes old rows first, but this stays list-based so a
    // stray duplicate degrades to "check them all" instead of a hard crash.
    List<NodeCredential> findAllByNodeIdAndRevokedAtIsNull(Long nodeId);
}
