package com.vpn.server.repository;

import com.vpn.server.entity.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TariffRepository extends JpaRepository<Tariff, String> {
    List<Tariff> findByIsActiveTrue();
}
