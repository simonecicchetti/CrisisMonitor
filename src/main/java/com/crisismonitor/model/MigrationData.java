package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationData {
    private LocalDate date;
    private String nationality;
    private String iso3;
    private String countryName;
    private Long count;
}
