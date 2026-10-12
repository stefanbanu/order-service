package com.banu.orderservice.controller;

import com.banu.orderservice.dto.CancelOrderRequest;
import com.banu.orderservice.dto.OrderRequest;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.dto.OrderStatusUpdateRequest;
import com.banu.orderservice.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** @Valid is what activates the OrderRequest constraints; without it they never run. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        OrderResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/orders/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<OrderResponse> findAll(@RequestParam(required = false) String email) {
        return service.findAll(email);
    }

    /** 200 with the updated order; 404 if unknown, 409 if the transition is not allowed. */
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return service.updateStatus(id, request.status());
    }

    /**
     * 200 with the cancelled order; 400 if the reason is longer than the @Size
     * limit, 404 if unknown, 409 if it is already shipped or was cancelled
     * concurrently, 502 if the refund failed.
     *
     * <p>{@code required = false} is what makes the body optional, so a bare
     * POST with no reason works; @Valid still has to stay or the @Size on the
     * reason never runs.
     */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return service.cancel(id, request == null ? null : request.reason());
    }
}
