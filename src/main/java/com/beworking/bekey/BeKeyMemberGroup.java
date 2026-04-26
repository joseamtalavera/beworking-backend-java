package com.beworking.bekey;                              

import jakarta.persistence.*;                                                                   
import java.time.OffsetDateTime;

@Entity
@Table(name = "bekey_member_groups", schema = "beworking")
public class BeKeyMemberGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "akiles_group_id", nullable = false, unique = true, length = 64)
    private String akilesGroupId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "scope", nullable = false, length = 64)
    private String scope;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public BeKeyMemberGroup() {}

    public Long getId() { return id; }                                                          
    public void setId(Long id) { this.id = id; }
                                                                                                  
    public String getAkilesGroupId() { return akilesGroupId; }
    public void setAkilesGroupId(String akilesGroupId) { this.akilesGroupId = akilesGroupId; }
                                                                                                  
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }                                  
                                                            
    public String getScope() { return scope; }                                                  
    public void setScope(String scope) { this.scope = scope; }
                                                                                                  
    public String getNotes() { return notes; }            
    public void setNotes(String notes) { this.notes = notes; }
                                                                                                  
    public OffsetDateTime getCreatedAt() { return createdAt; }
}