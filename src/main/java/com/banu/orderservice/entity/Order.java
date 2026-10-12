package com.banu.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Mapped to "orders" because ORDER is a reserved word in SQL; H2 rejects
 * {@code create table order (...)} with a syntax error.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Optimistic lock, not part of the API: OrderResponse never exposes it. Two
     * concurrent cancels both read version n, the first flush writes n+1, and the
     * second fails its version check — which is what stops a refund happening
     * twice.
     *
     * <p>Not nullable: under {@code ddl-auto: create-drop} a NULL version is
     * harmless, but once a migration tool arrives the column has to be added
     * {@code NOT NULL DEFAULT 0} and existing rows backfilled, or Hibernate reads
     * a NULL version as an unsaved (transient) entity and tries to INSERT it.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    private Instant cancelledAt;

    @Column(length = 255)
    private String cancellationReason;
}
