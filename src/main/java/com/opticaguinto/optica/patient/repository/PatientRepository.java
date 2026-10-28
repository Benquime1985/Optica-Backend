package com.opticaguinto.optica.patient.repository;

import com.opticaguinto.optica.patient.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, Long> {
  @Query("""
      select p from Patient p
      where p.active = true and (
        lower(p.firstName) like lower(concat('%', :search, '%')) or
        lower(p.lastName) like lower(concat('%', :search, '%')) or
        p.phone like concat('%', :search, '%'))
      """)
  Page<Patient> searchActive(@Param("search") String search, Pageable pageable);
}
