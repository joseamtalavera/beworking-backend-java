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
     * Desk subscriptions whose paid coverage period contains :date. A desk is
     * occupied while start_date <= date <= end_date, regardless of the `active`
     * flag — so a subscription cancelled mid-period (active=false, end_date set
     * to its paid-through date) still holds its desk until that date. This is
     * what prevents overbooking a desk that's been cancelled but is still paid.
     *
     * Coverage rule:
     *   · ongoing (end_date IS NULL) counts only while still active
     *   · otherwise the desk is held through end_date (the paid-through date)
     */
    @Query("SELECT s FROM Subscription s WHERE s.productoId IS NOT NULL AND s.startDate <= :date "
         + "AND ((s.endDate IS NULL AND s.active = true) OR s.endDate >= :date)")
    List<Subscription> findActiveCoveringDate(@Param("date") LocalDate date);
}
