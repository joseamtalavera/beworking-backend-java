package com.beworking.subscriptions;

import java.time.LocalDate;
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

    List<Subscription> findByActiveTrueAndProductoIdIsNotNull();

    @Query("SELECT s FROM Subscription s WHERE s.billingMethod = 'bank_transfer' AND s.active = true AND (s.lastInvoicedMonth IS NULL OR s.lastInvoicedMonth <> :month)")
    List<Subscription> findBankTransferDueForMonth(@Param("month") String month);

    /**
     * Active subscriptions whose coverage period contains :date. Used by the
     * public availability endpoint to mark subscribed-desk products as occupied
     * for the day, even when no per-day Bloqueo exists.
     */
    @Query("SELECT s FROM Subscription s WHERE s.active = true AND s.productoId IS NOT NULL AND s.startDate <= :date AND (s.endDate IS NULL OR s.endDate >= :date)")
    List<Subscription> findActiveCoveringDate(@Param("date") LocalDate date);
}
