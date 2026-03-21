package com.crisismonitor.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * User management service.
 * Creates/reads user profiles in Firestore.
 * Handles subscription tiers.
 *
 * >>> CHANGE POINT: add Stripe webhook handler for subscription activation
 * >>> CHANGE POINT: add subscription expiry check
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final FirestoreService firestoreService;

    // >>> CHANGE POINT: subscription tiers — add more tiers as needed
    public static final String TIER_FREE = "free";
    public static final String TIER_PRO = "pro";
    public static final String TIER_ADMIN = "admin";

    /**
     * Verify Firebase ID token from frontend.
     * Returns the decoded token (uid, email, name, photo).
     */
    public FirebaseToken verifyToken(String idToken) {
        try {
            return FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (Exception e) {
            log.warn("Invalid Firebase token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get or create user profile on login.
     * Called from /api/auth/login endpoint.
     * Creates Firestore doc on first login, returns existing on subsequent logins.
     *
     * >>> CHANGE POINT: add subscription fields when Stripe is integrated
     */
    public Map<String, Object> getOrCreateUser(String uid, String email, String name, String photoUrl) {
        // Check if user exists
        Map<String, Object> existing = firestoreService.getDocument("users", uid);
        if (existing != null) {
            // Update last login
            Map<String, Object> update = new HashMap<>();
            update.put("lastLoginAt", LocalDateTime.now().toString());
            if (name != null) update.put("name", name);
            if (photoUrl != null) update.put("photoUrl", photoUrl);
            update.put("timestamp", System.currentTimeMillis());
            firestoreService.saveDocument("users", uid, update);

            // Merge updates into existing
            existing.putAll(update);
            log.info("User login: {} ({})", email, existing.get("tier"));
            return existing;
        }

        // Create new user
        Map<String, Object> user = new HashMap<>();
        user.put("uid", uid);
        user.put("email", email);
        user.put("name", name != null ? name : "");
        user.put("photoUrl", photoUrl != null ? photoUrl : "");
        user.put("tier", TIER_FREE);  // >>> CHANGE POINT: default tier
        user.put("createdAt", LocalDateTime.now().toString());
        user.put("lastLoginAt", LocalDateTime.now().toString());
        user.put("reportsViewed", 0);
        user.put("timestamp", System.currentTimeMillis());

        // >>> CHANGE POINT: add subscription fields
        // user.put("subscriptionId", null);
        // user.put("subscriptionExpiresAt", null);
        // user.put("stripeCustomerId", null);

        firestoreService.saveDocument("users", uid, user);
        log.info("New user created: {} (tier={})", email, TIER_FREE);
        return user;
    }

    /**
     * Get user's subscription tier.
     */
    public String getUserTier(String uid) {
        Map<String, Object> user = firestoreService.getDocument("users", uid);
        if (user == null) return TIER_FREE;
        return (String) user.getOrDefault("tier", TIER_FREE);
    }

    /**
     * Check if user has pro access.
     *
     * >>> CHANGE POINT: add subscription expiry check
     */
    public boolean isProUser(String uid) {
        String tier = getUserTier(uid);
        return TIER_PRO.equals(tier) || TIER_ADMIN.equals(tier);
    }

    /**
     * Upgrade user to pro (called from Stripe webhook or admin).
     *
     * >>> CHANGE POINT: add Stripe subscription ID and expiry
     */
    public void upgradeToProUser(String uid) {
        Map<String, Object> update = new HashMap<>();
        update.put("tier", TIER_PRO);
        update.put("upgradedAt", LocalDateTime.now().toString());
        update.put("timestamp", System.currentTimeMillis());
        firestoreService.saveDocument("users", uid, update);
        log.info("User upgraded to PRO: {}", uid);
    }
}
