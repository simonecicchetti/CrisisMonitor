package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wrapper response for API endpoints with cache metadata
 *
 * Allows frontend to distinguish between:
 * - READY: Fresh data available
 * - LOADING: Data being fetched, check back soon
 * - STALE: Old data served while refreshing
 * - ERROR: Fetch failed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataResponse<T> {

    public enum Status {
        READY,      // Fresh data from cache
        LOADING,    // Data being fetched in background
        STALE,      // Serving old data while refreshing
        ERROR       // Fetch failed
    }

    private Status status;
    private T data;
    private String message;
    private LocalDateTime cachedAt;
    private LocalDateTime refreshingAt;
    private Integer retryAfterSeconds;

    /**
     * Create a ready response with fresh data
     */
    public static <T> DataResponse<T> ready(T data) {
        return DataResponse.<T>builder()
                .status(Status.READY)
                .data(data)
                .cachedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Create a loading response (cache cold, background fetch started)
     */
    public static <T> DataResponse<T> loading(String message) {
        return DataResponse.<T>builder()
                .status(Status.LOADING)
                .message(message != null ? message : "Data is being loaded, please wait...")
                .refreshingAt(LocalDateTime.now())
                .retryAfterSeconds(10)
                .build();
    }

    /**
     * Create a stale response (old data, refresh in progress)
     */
    public static <T> DataResponse<T> stale(T data, LocalDateTime cachedAt) {
        return DataResponse.<T>builder()
                .status(Status.STALE)
                .data(data)
                .cachedAt(cachedAt)
                .refreshingAt(LocalDateTime.now())
                .message("Data may be outdated, refreshing in background")
                .build();
    }

    /**
     * Create an error response
     */
    public static <T> DataResponse<T> error(String message) {
        return DataResponse.<T>builder()
                .status(Status.ERROR)
                .message(message)
                .retryAfterSeconds(30)
                .build();
    }
}
