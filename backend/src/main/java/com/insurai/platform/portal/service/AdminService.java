package com.insurai.platform.portal.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.insurai.platform.security.PlatformSecurityProperties;

import jakarta.annotation.PostConstruct;

import com.insurai.platform.portal.config.JwtUtil;
import com.insurai.platform.portal.model.Agent;
import com.insurai.platform.portal.model.Hr;
import com.insurai.platform.portal.model.RegisterRequest;
import com.insurai.platform.portal.repository.AgentRepository;
import com.insurai.platform.portal.repository.HrRepository;

@Service
public class AdminService {

    @Autowired
    private PlatformSecurityProperties securityProperties;

    /** BCrypt hash of the configured admin password, computed once at startup so plaintext is never compared. */
    private String adminPasswordHash;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private HrRepository hrRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    // -------------------- Admin login --------------------
    @PostConstruct
    void hashConfiguredAdminPassword() {
        this.adminPasswordHash = passwordEncoder.encode(securityProperties.admin().password());
    }

    public boolean validateAdmin(String email, String password) {
        // The credentials come from platform.security.admin.*; they used to be literals in this class.
        // Always run the BCrypt comparison so response time does not reveal whether the e-mail matched.
        boolean passwordMatches = password != null && passwordEncoder.matches(password, adminPasswordHash);
        return isConfiguredAdmin(email) && passwordMatches;
    }

    public String getAdminName(String email) {
        return isConfiguredAdmin(email) ? "Admin" : null;
    }

    private boolean isConfiguredAdmin(String email) {
        return email != null && securityProperties.admin().email().equalsIgnoreCase(email.trim());
    }

    public String getAdminRole() {
        return "ADMIN";
    }

    // -------------------- Generate JWT token --------------------
    public String generateAdminToken(String email) {
        return jwtUtil.generateToken(email, "ADMIN");
    }

    // -------------------- Verify JWT token --------------------
    public boolean isAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.out.println("[AdminService] Authorization header missing or invalid");
            return false;
        }

        String token = authHeader.substring(7).trim(); // Remove "Bearer " prefix
        try {
            String role = jwtUtil.extractRole(token);
            return "ADMIN".equalsIgnoreCase(role);
        } catch (Exception e) {
            System.out.println("[AdminService] Invalid JWT token: " + e.getMessage());
            return false;
        }
    }

    // -------------------- Register Agent --------------------
    public void registerAgent(RegisterRequest request) {
        Agent agent = new Agent();
        agent.setName(request.getName());
        agent.setEmail(request.getEmail());
        agent.setPassword(passwordEncoder.encode(request.getPassword()));
        agentRepository.save(agent);
    }

    // -------------------- Register HR --------------------
    public void registerHR(RegisterRequest request) {
        Hr hr = new Hr();
        hr.setName(request.getName());
        hr.setEmail(request.getEmail());
        hr.setPhoneNumber(request.getPhoneNumber());
        hr.setHrId(request.getHrId());
        hr.setPassword(passwordEncoder.encode(request.getPassword()));
        hrRepository.save(hr);
    }
}
