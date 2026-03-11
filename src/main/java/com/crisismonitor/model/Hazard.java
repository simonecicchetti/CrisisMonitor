package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hazard {
    private String name;
    private String type;        // FLOOD, EARTHQUAKE, CYCLONE, etc.
    private String severity;    // WARNING, WATCH, INFORMATION
    private String category;    // NATURAL, EXERCISE, etc.
    private Double latitude;
    private Double longitude;
    private LocalDateTime createDate;
    private LocalDateTime lastUpdate;
    private String status;      // A = Active
}
