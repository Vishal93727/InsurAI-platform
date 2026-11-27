package com.insurai.platform.portal.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.insurai.platform.portal.model.EmployeeQuery;

@Repository
public interface EmployeeQueryRepository extends JpaRepository<EmployeeQuery, Long> {

    // Get all queries submitted by a specific employee
    List<EmployeeQuery> findByEmployeeId(Long employeeId);

    // Get all queries assigned to a specific agent
    List<EmployeeQuery> findByAgentId(Long agentId);

    // Get all queries by employee and status (e.g., only pending)
    List<EmployeeQuery> findByEmployeeIdAndStatus(Long employeeId, String status);

    // Get all queries by agent and status (e.g., only pending)
    List<EmployeeQuery> findByAgentIdAndStatus(Long agentId, String status);

    // Get all queries with a specific status (e.g., pending) regardless of agent
    List<EmployeeQuery> findByStatus(String status);
    
}
