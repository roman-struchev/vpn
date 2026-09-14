package com.vpn.server.repository;

import com.vpn.server.entity.NodeBootstrapToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NodeBootstrapTokenRepository extends JpaRepository<NodeBootstrapToken, Long> {
    // Multi-use within the token's validity window (see registerNode) — the
    // expiry check there is what actually gates redemption now, not isUsed.
    Optional<NodeBootstrapToken> findByToken(String token);
}
