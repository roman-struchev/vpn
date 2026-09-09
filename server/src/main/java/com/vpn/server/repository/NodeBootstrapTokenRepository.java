package com.vpn.server.repository;

import com.vpn.server.entity.NodeBootstrapToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NodeBootstrapTokenRepository extends JpaRepository<NodeBootstrapToken, Long> {
    Optional<NodeBootstrapToken> findByTokenAndIsUsedFalse(String token);
}
