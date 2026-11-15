package com.cloudpulse.inventory.repo;

import com.cloudpulse.inventory.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, String> {}
