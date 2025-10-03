package com.beworking.contacts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactProfileRepository extends JpaRepository<ContactProfile, Long>, JpaSpecificationExecutor<ContactProfile> {}
