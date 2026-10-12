package com.banu.orderservice.config;

import com.banu.orderservice.client.FakePaymentClient;
import com.banu.orderservice.client.PaymentClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fallback {@link PaymentClient}. The point of the indirection is
 * that a real provider becomes a drop-in replacement: declare any other
 * {@code PaymentClient} bean and this factory method backs off, instead of
 * startup failing with {@code NoUniqueBeanDefinitionException} the way an
 * unconditional {@code @Component} on {@link FakePaymentClient} did.
 *
 * <p>{@link ConditionalOnMissingBean} is only reliable on {@code @Bean} methods —
 * on a {@code @Component} it races against classpath scanning order — which is
 * why the fake moved behind a factory method rather than keeping the annotation
 * on the class.
 *
 * <p>Exactly one {@code PaymentClient} is always defined, which is what lets a
 * test replace it wholesale by type with {@code @MockitoBean}.
 */
@Configuration
public class PaymentClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentClient.class)
    PaymentClient fakePaymentClient() {
        return new FakePaymentClient();
    }
}
