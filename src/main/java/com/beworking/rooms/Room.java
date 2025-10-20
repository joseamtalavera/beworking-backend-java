package com.beworking.rooms;

import jakarta.persistence.Column;   
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms", schema = "beworking")
public class Room {

    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; //eg: "MA1A1"
    
    @Column(nullable = false, unique = true, length = 32) 
    private String code; //eg: "MA1A1 - Calle Alejandro Dumas 17, 29004"

    @Column(nullable = false, length = 160) 
    private String name;

    @Column(length = 255)
    private String subtitle;

    @Column(length = 2000)
    private String description;

    @Column(length = 255)
    private String address;

    @Column(length = 32)
    private String city;

    @Column(length = 32)
    private String postalCode;

    @Column(length = 32)
    private String country;

    @Column(length = 64)
    private String region;

}
