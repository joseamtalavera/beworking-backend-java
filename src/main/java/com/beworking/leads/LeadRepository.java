package com.beworking.leads;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /**
     * Hard-deletes every lead whose email matches (case-insensitive). Called on
     * lead → customer conversion so a person who later registers stops appearing
     * in the leads pipeline. Returns the number of rows deleted.
     */
    @Transactional
    long deleteByEmailIgnoreCase(String email);

    /**
     * Lookup helper for diagnostics / dashboard.
     */
    List<Lead> findByEmailIgnoreCase(String email);

    /**
     * Search-and-paginate for the admin Leads tab. Matches name / email /
     * phone / subject case-insensitively. Pass {@code null} or empty {@code q}
     * to list everything (paged).
     */
    @Query("""
        select l from Lead l
        where (:q is null or :q = '' or
               lower(l.name)    like lower(concat('%', :q, '%')) or
               lower(l.email)   like lower(concat('%', :q, '%')) or
               lower(l.phone)   like lower(concat('%', :q, '%')) or
               lower(l.subject) like lower(concat('%', :q, '%')))
        """)
    Page<Lead> search(@Param("q") String q, Pageable pageable);
}
