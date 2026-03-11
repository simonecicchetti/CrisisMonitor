package com.crisismonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * IPC (Integrated Food Security Phase Classification) Alert
 *
 * IPC Phases:
 * 1 - Minimal: Households can meet essential food and non-food needs
 * 2 - Stressed: Households have minimally adequate food but cannot afford some essential non-food expenditures
 * 3 - Crisis: Households have food consumption gaps OR are marginally able to meet minimum food needs
 * 4 - Emergency: Households have large food consumption gaps OR extreme loss of livelihood assets
 * 5 - Famine: Households have extreme lack of food, starvation, death, and destitution evident
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IPCAlert {

    private String iso2;
    private String countryName;
    private String region;          // Sub-national area (can be null for national level)

    private Double ipcPhase;        // 1.0 - 5.0
    private String phaseDescription; // "Minimal", "Stressed", "Crisis", "Emergency", "Famine"

    private LocalDate projectionStart;
    private LocalDate projectionEnd;

    private String scenario;        // "CS" = Current Situation, "ML" = Most Likely
    private String source;          // "FEWS NET"

    /**
     * Get severity level for UI styling
     */
    @JsonIgnore
    public String getSeverity() {
        if (ipcPhase == null) return "unknown";
        if (ipcPhase >= 5.0) return "famine";
        if (ipcPhase >= 4.0) return "emergency";
        if (ipcPhase >= 3.0) return "crisis";
        if (ipcPhase >= 2.0) return "stressed";
        return "minimal";
    }

    /**
     * Is this alert critical (Phase 3+)?
     */
    @JsonIgnore
    public boolean isCritical() {
        return ipcPhase != null && ipcPhase >= 3.0;
    }
}
