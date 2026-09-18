package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One distinct failure seen somewhere in the fleet, aggregated across every
 * occurrence of it (see V12__diagnostic_events.sql for why it is stored this
 * way rather than one row per report).
 */
@Entity
@Table(name = "diagnostic_events")
public class DiagnosticEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, length = 8)
    private String severity = "ERROR";

    @Column(nullable = false, length = 64)
    private String component = "unknown";

    @Column(nullable = false, length = 64)
    private String code = "UNSPECIFIED";

    @Column(nullable = false, length = 512)
    private String message;

    @Column(name = "sample_detail", columnDefinition = "text")
    private String sampleDetail;

    @Column(name = "sample_context", columnDefinition = "text")
    private String sampleContext;

    /** Comma-separated, oldest first — see DiagnosticsService#mergeAppVersions. */
    @Column(name = "app_versions", columnDefinition = "text")
    private String appVersions;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_user_id")
    private User lastUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_node_id")
    private Node lastNode;

    @Column(nullable = false)
    private Long occurrences = 1L;

    @Column(nullable = false)
    private Long reporters = 1L;

    @Column(name = "last_reporter", length = 64)
    private String lastReporter;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    public DiagnosticEvent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSampleDetail() { return sampleDetail; }
    public void setSampleDetail(String sampleDetail) { this.sampleDetail = sampleDetail; }

    public String getSampleContext() { return sampleContext; }
    public void setSampleContext(String sampleContext) { this.sampleContext = sampleContext; }

    public String getAppVersions() { return appVersions; }
    public void setAppVersions(String appVersions) { this.appVersions = appVersions; }

    // @JsonIgnore for the same reason Node#getOwnerUser has it: a LAZY
    // relation blows up if something serializes this entity outside an open
    // Hibernate session. The scalar ids below are the safe view.
    @JsonIgnore
    public User getLastUser() { return lastUser; }
    public void setLastUser(User lastUser) { this.lastUser = lastUser; }

    @JsonIgnore
    public Node getLastNode() { return lastNode; }
    public void setLastNode(Node lastNode) { this.lastNode = lastNode; }

    public Long getLastUserId() { return lastUser != null ? lastUser.getId() : null; }

    public Long getLastNodeId() { return lastNode != null ? lastNode.getId() : null; }

    public Long getOccurrences() { return occurrences; }
    public void setOccurrences(Long occurrences) { this.occurrences = occurrences; }

    public Long getReporters() { return reporters; }
    public void setReporters(Long reporters) { this.reporters = reporters; }

    public String getLastReporter() { return lastReporter; }
    public void setLastReporter(String lastReporter) { this.lastReporter = lastReporter; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
