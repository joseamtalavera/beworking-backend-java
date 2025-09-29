package com.beworking.mailroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailroomDocumentRepository extends JpaRepository<MailroomDocument, UUID> {
    List<MailroomDocument> findTop100ByOrderByReceivedAtDescCreatedAtDesc();

    default List<MailroomDocument> findRecentDocuments() {
        return findTop100ByOrderByReceivedAtDescCreatedAtDesc();
    }
}
