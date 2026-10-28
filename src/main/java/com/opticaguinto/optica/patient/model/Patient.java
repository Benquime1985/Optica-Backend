package com.opticaguinto.optica.patient.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "patients")
public class Patient {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "first_name", nullable = false, length = 100)
  private String firstName;
  @Column(name = "last_name", nullable = false, length = 200)
  private String lastName;
  @Column(name = "birth_date")
  private LocalDate birthDate;
  @Column(length = 30)
  private String phone;
  @Column(length = 254)
  private String email;
  @Column(length = 4000)
  private String notes;
  @Column(nullable = false)
  private boolean active = true;
  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected Patient() { }

  public Patient(String firstName, String lastName, LocalDate birthDate, String phone, String email, String notes) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.birthDate = birthDate;
    this.phone = phone;
    this.email = email;
    this.notes = notes;
  }

  public Long getId() { return id; }
  public String getFirstName() { return firstName; }
  public String getLastName() { return lastName; }
  public LocalDate getBirthDate() { return birthDate; }
  public String getPhone() { return phone; }
  public String getEmail() { return email; }
  public String getNotes() { return notes; }
  public boolean isActive() { return active; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  public void update(String firstName, String lastName, LocalDate birthDate, String phone, String email, String notes) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.birthDate = birthDate;
    this.phone = phone;
    this.email = email;
    this.notes = notes;
  }

  public void archive() { this.active = false; }
}
