package com.insurai.platform.portal.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.insurai.platform.portal.model.Admin;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByEmail(String email);
}
