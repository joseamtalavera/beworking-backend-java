 package com.beworking.notifications;

  import java.util.List;
  import java.util.UUID;
  import org.springframework.data.jpa.repository.JpaRepository;
  import org.springframework.data.jpa.repository.Query;
  import org.springframework.data.repository.query.Param;

  public interface NotificationRepository extends JpaRepository<Notification, UUID> {

      // All notifications for one recipient, case-insensitive, newest first.
      @Query("SELECT n FROM Notification n "
           + "WHERE LOWER(n.contactEmail) = LOWER(:email) "
           + "ORDER BY n.createdAt DESC")
      List<Notification> findByContactEmail(@Param("email") String email);

      // Most recent notifications across all contacts (admin overview), newest first.
      List<Notification> findTop200ByOrderByCreatedAtDesc();
  }
  