package com.beworking.rooms;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms", schema = "beworking")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

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

    @Column(name = "centro_code", length = 32)
    private String centroCode;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "creation_date")
    private LocalDate creationDate;

    @Column(name = "size_sqm")
    private Integer sizeSqm;

    @Column
    private Integer capacity;

    @Column(name = "price_from", precision = 10, scale = 2)
    private BigDecimal priceFrom;

    @Column(name = "price_unit", length = 16)
    private String priceUnit;

    @Column(name = "price_hour_min", precision = 10, scale = 2)
    private BigDecimal priceHourMin;

    @Column(name = "price_hour_med", precision = 10, scale = 2)
    private BigDecimal priceHourMed;

    @Column(name = "price_hour_max", precision = 10, scale = 2)
    private BigDecimal priceHourMax;

    @Column(name = "price_day", precision = 10, scale = 2)
    private BigDecimal priceDay;

    @Column(name = "price_month", precision = 10, scale = 2)
    private BigDecimal priceMonth;

    @Column(name = "wifi_credentials", length = 255)
    private String wifiCredentials;

    @Column(name = "video_url", length = 512)
    private String videoUrl;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "rating_avg", precision = 4, scale = 2)
    private BigDecimal ratingAverage;

    @Column(name = "rating_count")
    private Integer ratingCount;

    @Column(name = "instant_booking")
    private boolean instantBooking;

    @Column(length = 255)
    private String tags;

    @Column(name = "hero_image", length = 512)
    private String heroImage;

    @OneToMany(
        mappedBy = "room",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    private List<RoomImage> images = new ArrayList<>();

    @OneToMany(
        mappedBy = "room",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    private List<RoomAmenity> amenities = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCentroCode() {
        return centroCode;
    }

    public void setCentroCode(String centroCode) {
        this.centroCode = centroCode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(LocalDate creationDate) {
        this.creationDate = creationDate;
    }

    public Integer getSizeSqm() {
        return sizeSqm;
    }

    public void setSizeSqm(Integer sizeSqm) {
        this.sizeSqm = sizeSqm;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public BigDecimal getPriceFrom() {
        return priceFrom;
    }

    public void setPriceFrom(BigDecimal priceFrom) {
        this.priceFrom = priceFrom;
    }

    public String getPriceUnit() {
        return priceUnit;
    }

    public void setPriceUnit(String priceUnit) {
        this.priceUnit = priceUnit;
    }

    public BigDecimal getPriceHourMin() {
        return priceHourMin;
    }

    public void setPriceHourMin(BigDecimal priceHourMin) {
        this.priceHourMin = priceHourMin;
    }

    public BigDecimal getPriceHourMed() {
        return priceHourMed;
    }

    public void setPriceHourMed(BigDecimal priceHourMed) {
        this.priceHourMed = priceHourMed;
    }

    public BigDecimal getPriceHourMax() {
        return priceHourMax;
    }

    public void setPriceHourMax(BigDecimal priceHourMax) {
        this.priceHourMax = priceHourMax;
    }

    public BigDecimal getPriceDay() {
        return priceDay;
    }

    public void setPriceDay(BigDecimal priceDay) {
        this.priceDay = priceDay;
    }

    public BigDecimal getPriceMonth() {
        return priceMonth;
    }

    public void setPriceMonth(BigDecimal priceMonth) {
        this.priceMonth = priceMonth;
    }

    public String getWifiCredentials() {
        return wifiCredentials;
    }

    public void setWifiCredentials(String wifiCredentials) {
        this.wifiCredentials = wifiCredentials;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public BigDecimal getRatingAverage() {
        return ratingAverage;
    }

    public void setRatingAverage(BigDecimal ratingAverage) {
        this.ratingAverage = ratingAverage;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(Integer ratingCount) {
        this.ratingCount = ratingCount;
    }

    public boolean isInstantBooking() {
        return instantBooking;
    }

    public void setInstantBooking(boolean instantBooking) {
        this.instantBooking = instantBooking;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getHeroImage() {
        return heroImage;
    }

    public void setHeroImage(String heroImage) {
        this.heroImage = heroImage;
    }

    public List<RoomImage> getImages() {
        return images;
    }

    public void setImages(List<RoomImage> images) {
        this.images = images;
    }

    public List<RoomAmenity> getAmenities() {
        return amenities;
    }

    public void setAmenities(List<RoomAmenity> amenities) {
        this.amenities = amenities;
    }
}
