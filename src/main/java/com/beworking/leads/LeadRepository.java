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
     * phone / subject case-insensitively. Caller is responsible for passing
     * a non-blank pattern (e.g. {@code "%alice%"}) — when no search term is
     * provided, use {@link #findAll(Pageable)} from JpaRepository instead.
     *
     * <p>Uses {@code coalesce(..., '')} so NULL columns (phone is now nullable)
     * collapse to empty string instead of returning NULL from LIKE.
     */
    @Query("""
        select l from Lead l
        where lower(coalesce(l.name, ''))    like :pattern
           or lower(coalesce(l.email, ''))   like :pattern
           or lower(coalesce(l.phone, ''))   like :pattern
           or lower(coalesce(l.subject, '')) like :pattern
        """)
    Page<Lead> searchByPattern(@Param("pattern") String pattern, Pageable pageable);
}
