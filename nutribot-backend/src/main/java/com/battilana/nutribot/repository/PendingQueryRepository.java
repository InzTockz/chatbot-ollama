package com.battilana.nutribot.repository;

import com.battilana.nutribot.model.PendingQuery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingQueryRepository extends JpaRepository<PendingQuery, Long> {
}
