package com.beworking.subscriptions;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Integer> {

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    List<Subscription> findByActiveTrue();

    List<Subscription> findByContactId(Long contactId);

    List<Subscription> findByContactIdAndActiveTrue(Long contactId);
}
