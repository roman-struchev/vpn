package com.vpn.server.repository;

import com.vpn.server.entity.TransportPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransportPolicyRepository extends JpaRepository<TransportPolicy, Long> {
    Optional<TransportPolicy> findFirstByScopeAndScopeValueAndIsActiveTrue(String scope, String scopeValue);
    List<TransportPolicy> findByIsActiveTrue();
}
