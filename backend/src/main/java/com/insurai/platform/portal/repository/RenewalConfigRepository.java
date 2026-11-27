package com.insurai.platform.portal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.insurai.platform.portal.model.RenewalConfig;

@Repository
public interface RenewalConfigRepository extends JpaRepository<RenewalConfig, Long> {

    Optional<RenewalConfig> findTopByOrderByIdDesc();
}
