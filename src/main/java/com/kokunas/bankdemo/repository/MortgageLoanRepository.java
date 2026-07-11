package com.kokunas.bankdemo.repository;

import com.kokunas.bankdemo.model.MortgageLoan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MortgageLoanRepository extends JpaRepository<MortgageLoan, Long> {
}
