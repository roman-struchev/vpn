package com.vpn.server.repository;

import com.vpn.server.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {
    Optional<Node> findByHostname(String hostname);
    List<Node> findByPoolAndStatus(String pool, String status);
    List<Node> findByPoolInAndStatus(Collection<String> pools, String status);
    List<Node> findByPoolAndRegionAndStatus(String pool, String region, String status);
    List<Node> findByStatus(String status);
    long countByStatus(String status);
}
