package com.insurai.platform.portal.service;

import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.insurai.platform.portal.model.Employee;
import com.insurai.platform.portal.model.Hr;
import com.insurai.platform.portal.model.RegisterRequest;
import com.insurai.platform.portal.repository.EmployeeRepository;
import com.insurai.platform.portal.repository.HrRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HrService {

    private final HrRepository hrRepository;
    private final EmployeeRepository employeeRepository; // added for fetching employee name
    private final PasswordEncoder passwordEncoder;

    // -------------------- Register HR --------------------
    public Hr registerHR(RegisterRequest request) {
        Hr hr = new Hr();
        hr.setName(request.getName());
        hr.setEmail(request.getEmail());
        hr.setPhoneNumber(request.getPhoneNumber());
        hr.setHrId(request.getHrId());
        hr.setPassword(passwordEncoder.encode(request.getPassword()));

        return hrRepository.save(hr);
    }

    // -------------------- Find HR by email --------------------
    public Optional<Hr> findByEmail(String email) {
        return hrRepository.findByEmail(email);
    }

    // -------------------- Validate HR credentials --------------------
    public boolean validateCredentials(String email, String rawPassword) {
        Optional<Hr> optionalHr = hrRepository.findByEmail(email);
        if (optionalHr.isEmpty()) return false;

        Hr hr = optionalHr.get();
        return passwordEncoder.matches(rawPassword, hr.getPassword());
    }

    // -------------------- Get all active HRs --------------------
    public List<Hr> getAllActiveHrs() {
        // Assuming "active" HRs means those present in the database
        return hrRepository.findAll();
    }

    // -------------------- Get Employee Name by ID --------------------
    public String getEmployeeNameById(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .map(Employee::getName)
                .orElse("Unknown Employee");
    }
}
