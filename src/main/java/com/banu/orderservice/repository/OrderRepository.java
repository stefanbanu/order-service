package com.banu.orderservice.repository;

import com.banu.orderservice.entity.Order;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Case-insensitive because email domains are case-insensitive, so an exact
     * match would miss an order stored as bob@x.com when searching Bob@x.com.
     */
    List<Order> findByCustomerEmailIgnoreCase(String customerEmail);
}
