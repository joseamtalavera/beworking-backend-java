package com.beworking.subscriptions;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Integer> {

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Subscription> findFirstByStripeCustomerIdAndActiveTrue(String stripeCustomerId);

    List<Subscription> findByActiveTrue();

    List<Subscription> findByContactId(Long contactId);

    List<Subscription> findByContactIdAndActiveTrue(Long contactId);

    @Query("SELECT s FROM Subscription s WHERE s.billingMethod = 'bank_transfer' AND s.active = true AND (s.lastInvoicedMonth IS NULL OR s.lastInvoicedMonth <> :month)")
    List<Subscription> findBankTransferDueForMonth(@Param("month") String month);
}
