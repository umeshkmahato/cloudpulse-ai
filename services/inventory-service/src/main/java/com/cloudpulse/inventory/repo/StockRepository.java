package com.cloudpulse.inventory.repo;

import com.cloudpulse.inventory.domain.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StockRepository extends JpaRepository<StockItem, String> {
    /** Row locks in SKU order: concurrent reservations serialise per SKU, no oversell, no lock-order deadlocks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.sku in :skus order by s.sku")
    List<StockItem> lockBySkus(Collection<String> skus);
}
