package com.banu.orderservice.service;

import com.banu.orderservice.dto.OrderRequest;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.exception.InvalidStatusTransitionException;
import com.banu.orderservice.exception.OrderNotFoundException;
import com.banu.orderservice.repository.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = new Order();
        order.setCustomerEmail(request.customerEmail());
        order.setAmount(request.amount());
        order.setStatus(OrderStatus.NEW);
        return OrderResponse.from(repository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return repository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /**
     * The transition matrix lives on {@link OrderStatus}; this only enforces it.
     * A disallowed move throws rather than quietly leaving the status alone, so a
     * client that PATCHes a shipped order learns nothing happened.
     */
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus target) {
        Order order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (!order.getStatus().canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, order.getStatus(), target);
        }
        order.setStatus(target);
        return OrderResponse.from(repository.save(order));
    }

    /**
     * @param email filters by customer when provided; a null or blank value
     *              lists every order.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll(String email) {
        List<Order> orders = StringUtils.hasText(email)
                ? repository.findByCustomerEmailIgnoreCase(email)
                : repository.findAll();
        return orders.stream().map(OrderResponse::from).toList();
    }
}
