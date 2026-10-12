package com.banu.orderservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Also the canary for the reserved-word trap: this context cannot start if the
 * entity is mapped to "order" rather than "orders".
 */
@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private OrderRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private Order persist(String email, String amount) {
        Order order = new Order();
        order.setCustomerEmail(email);
        order.setAmount(new BigDecimal(amount));
        order.setStatus(OrderStatus.NEW);
        return repository.saveAndFlush(order);
    }

    @Test
    void populatesIdStatusAndCreatedAtOnSave() {
        Order saved = persist("bob@example.com", "42.50");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findsByExactEmail() {
        persist("bob@example.com", "10.00");

        List<Order> found = repository.findByCustomerEmailIgnoreCase("bob@example.com");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getCustomerEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void findsByEmailRegardlessOfCase() {
        persist("bob@example.com", "10.00");

        assertThat(repository.findByCustomerEmailIgnoreCase("BOB@EXAMPLE.COM")).hasSize(1);
        assertThat(repository.findByCustomerEmailIgnoreCase("Bob@Example.Com")).hasSize(1);
    }

    @Test
    void returnsEmptyForUnrelatedEmail() {
        persist("bob@example.com", "10.00");

        assertThat(repository.findByCustomerEmailIgnoreCase("alice@example.com")).isEmpty();
    }

    @Test
    void preservesAmountScale() {
        Order saved = persist("bob@example.com", "42.50");

        Order reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAmount()).isEqualByComparingTo("42.50");
    }

    @Test
    void incrementsTheVersionOnEveryUpdate() {
        Order saved = persist("bob@example.com", "42.50");
        Long firstVersion = saved.getVersion();
        assertThat(firstVersion).isNotNull();

        saved.setStatus(OrderStatus.PAID);
        Order updated = repository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(firstVersion + 1);
    }

    /**
     * The single-threaded stand-in for two concurrent cancels: a write carrying
     * the version the caller last read is rejected once someone else has moved
     * the row on. OrderService depends on this failing, because it is what stops
     * a second cancel reaching the refund.
     */
    @Test
    void rejectsAWriteThatCarriesAStaleVersion() {
        Order saved = persist("bob@example.com", "42.50");
        Long id = saved.getId();
        Long staleVersion = saved.getVersion();

        saved.setStatus(OrderStatus.PAID);
        repository.saveAndFlush(saved);
        // Detach everything, so what follows is a merge of a stale copy rather
        // than a write through the instance the persistence context already holds.
        entityManager.clear();

        Order stale = new Order();
        stale.setId(id);
        stale.setCustomerEmail("bob@example.com");
        stale.setAmount(new BigDecimal("42.50"));
        stale.setStatus(OrderStatus.CANCELLED);
        stale.setVersion(staleVersion);

        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
