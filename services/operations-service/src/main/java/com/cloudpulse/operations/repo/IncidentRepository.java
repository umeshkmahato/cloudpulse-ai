package com.cloudpulse.operations.repo;

import com.cloudpulse.operations.domain.Incident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findFirstByServiceAndStatus(String service, String status);

    List<Incident> findByStatusOrderByOpenedAtDesc(String status, Pageable page);

    List<Incident> findAllByOrderByOpenedAtDesc(Pageable page);
}
