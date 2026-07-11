package com.kokunas.bankdemo.repository;

import com.kokunas.bankdemo.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
