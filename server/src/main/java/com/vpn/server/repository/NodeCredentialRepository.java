package com.vpn.server.repository;

import com.vpn.server.entity.NodeCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NodeCredentialRepository extends JpaRepository<NodeCredential, Long> {
    Optional<NodeCredential> findByNodeIdAndRevokedAtIsNull(Long nodeId);
}
