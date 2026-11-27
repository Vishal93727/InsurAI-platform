package com.insurai.platform.portal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.insurai.platform.portal.model.Agent;
import com.insurai.platform.portal.model.AgentAvailability;

@Repository
public interface AgentAvailabilityRepository extends JpaRepository<AgentAvailability, Long> {

    // ✅ Get the latest availability record for a specific agent
    Optional<AgentAvailability> findTopByAgentOrderByIdDesc(Agent agent);

    // ✅ Get all agent availability records sorted by ID descending
    List<AgentAvailability> findAllByOrderByIdDesc();
}
