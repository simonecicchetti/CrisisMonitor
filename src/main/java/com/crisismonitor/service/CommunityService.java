package com.crisismonitor.service;

import com.google.cloud.firestore.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Community engagement service — comments and reactions on intelligence reports.
 *
 * Patterns adapted from Notamy backend:
 * - Cursor-based pagination (not offset)
 * - FieldValue.increment() for atomic counters
 * - Reads-first-then-writes in transactions
 * - Soft delete for comments
 *
 * Firestore collections:
 * - reportComments/{commentId}
 * - reportReactions/{reactionId}  (format: {reportId}_{userId}_{type})
 * - generatedReports/{reportId}.reactions (denormalized counts)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private final Firestore firestore;

    // ==========================================
    // COMMENTS
    // ==========================================

    @Data
    public static class Comment {
        private String id;
        private String reportId;
        private String userId;
        private String userName;
        private String userPhoto;
        private String text;
        private long createdAt;
        private boolean deleted;
        private int likesCount;
    }

    @Data
    public static class CommentPage {
        private List<Comment> comments;
        private boolean hasMore;
        private String nextCursor;
    }

    /**
     * Add a comment to a report.
     * Requires authenticated user (token verified by caller).
     */
    public Comment addComment(String reportId, String userId, String userName, String userPhoto, String text) {
        if (firestore == null) return null;
        if (text == null || text.isBlank() || text.length() > 500) return null;

        try {
            String commentId = UUID.randomUUID().toString();
            long now = Instant.now().toEpochMilli();

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", commentId);
            doc.put("reportId", reportId);
            doc.put("userId", userId);
            doc.put("userName", userName != null ? userName : "");
            doc.put("userPhoto", userPhoto != null ? userPhoto : "");
            doc.put("text", text.trim());
            doc.put("createdAt", now);
            doc.put("deleted", false);
            doc.put("likesCount", 0);

            firestore.collection("reportComments").document(commentId).set(doc).get();

            // Increment comment count on report (atomic)
            try {
                firestore.collection("generatedReports").document(reportId)
                    .update("reactions.comments", FieldValue.increment(1)).get();
            } catch (Exception e) {
                // Report may not have reactions field yet — initialize
                firestore.collection("generatedReports").document(reportId)
                    .set(Map.of("reactions", Map.of("comments", 1)), SetOptions.merge()).get();
            }

            Comment c = new Comment();
            c.setId(commentId);
            c.setReportId(reportId);
            c.setUserId(userId);
            c.setUserName(userName);
            c.setUserPhoto(userPhoto);
            c.setText(text.trim());
            c.setCreatedAt(now);
            c.setLikesCount(0);

            log.info("Comment added on {}: {} by {}", reportId, commentId, userName);
            return c;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to add comment: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get comments for a report (cursor-based pagination, chronological).
     */
    public CommentPage getComments(String reportId, int limit, String cursor) {
        if (firestore == null) return null;
        if (limit < 1) limit = 20;
        if (limit > 50) limit = 50;

        try {
            Query query = firestore.collection("reportComments")
                .whereEqualTo("reportId", reportId)
                .whereEqualTo("deleted", false)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(limit + 1); // +1 to check hasMore

            if (cursor != null && !cursor.isBlank()) {
                long cursorTs = Long.parseLong(cursor);
                query = query.startAfter(cursorTs);
            }

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();

            CommentPage page = new CommentPage();
            page.setHasMore(docs.size() > limit);

            List<Comment> comments = new ArrayList<>();
            for (int i = 0; i < Math.min(docs.size(), limit); i++) {
                QueryDocumentSnapshot doc = docs.get(i);
                Comment c = new Comment();
                c.setId(doc.getString("id"));
                c.setReportId(doc.getString("reportId"));
                c.setUserId(doc.getString("userId"));
                c.setUserName(doc.getString("userName"));
                c.setUserPhoto(doc.getString("userPhoto"));
                c.setText(doc.getString("text"));
                c.setCreatedAt(doc.getLong("createdAt") != null ? doc.getLong("createdAt") : 0);
                c.setLikesCount(doc.getLong("likesCount") != null ? doc.getLong("likesCount").intValue() : 0);
                comments.add(c);
            }

            page.setComments(comments);
            if (!comments.isEmpty()) {
                page.setNextCursor(String.valueOf(comments.get(comments.size() - 1).getCreatedAt()));
            }

            return page;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get comments: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Delete a comment (soft delete). Only the comment author can delete.
     */
    public boolean deleteComment(String commentId, String userId) {
        if (firestore == null) return false;

        try {
            DocumentSnapshot doc = firestore.collection("reportComments").document(commentId).get().get();
            if (!doc.exists()) return false;
            if (!userId.equals(doc.getString("userId"))) return false; // Not the author

            String reportId = doc.getString("reportId");
            firestore.collection("reportComments").document(commentId)
                .update("deleted", true).get();

            // Decrement comment count
            if (reportId != null) {
                firestore.collection("generatedReports").document(reportId)
                    .update("reactions.comments", FieldValue.increment(-1)).get();
            }

            return true;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete comment: {}", e.getMessage());
            return false;
        }
    }

    // ==========================================
    // REACTIONS
    // ==========================================

    @Data
    public static class ReactionCounts {
        private int useful;
        private int insightful;
        private int verify;
        private int comments;
        private String userReaction; // What the current user reacted with (null if none)
    }

    /**
     * Toggle a reaction on a report.
     * One reaction per user per report. Clicking same type = remove. Different type = replace.
     */
    public ReactionCounts toggleReaction(String reportId, String userId, String reactionType) {
        if (firestore == null) return null;
        if (!List.of("useful", "insightful", "verify").contains(reactionType)) return null;

        try {
            String reactionId = reportId + "_" + userId;
            DocumentReference reactionRef = firestore.collection("reportReactions").document(reactionId);
            DocumentReference reportRef = firestore.collection("generatedReports").document(reportId);

            // Transaction: read first, then write
            firestore.runTransaction(tx -> {
                DocumentSnapshot existing = tx.get(reactionRef).get();

                if (existing.exists()) {
                    String oldType = existing.getString("reactionType");
                    if (reactionType.equals(oldType)) {
                        // Same reaction — remove it
                        tx.delete(reactionRef);
                        tx.update(reportRef, "reactions." + oldType, FieldValue.increment(-1));
                    } else {
                        // Different reaction — replace
                        tx.update(reactionRef, Map.of(
                            "reactionType", reactionType,
                            "updatedAt", Instant.now().toEpochMilli()
                        ));
                        tx.update(reportRef, "reactions." + oldType, FieldValue.increment(-1));
                        tx.update(reportRef, "reactions." + reactionType, FieldValue.increment(1));
                    }
                } else {
                    // New reaction
                    tx.set(reactionRef, Map.of(
                        "reportId", reportId,
                        "userId", userId,
                        "reactionType", reactionType,
                        "createdAt", Instant.now().toEpochMilli()
                    ));
                    tx.update(reportRef, "reactions." + reactionType, FieldValue.increment(1));
                }
                return null;
            }).get();

            // Return updated counts
            return getReactionCounts(reportId, userId);

        } catch (Exception e) {
            log.error("Failed to toggle reaction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get reaction counts for a report + current user's reaction.
     */
    public ReactionCounts getReactionCounts(String reportId, String userId) {
        if (firestore == null) return null;

        try {
            ReactionCounts counts = new ReactionCounts();

            // Get counts from report document
            DocumentSnapshot report = firestore.collection("generatedReports").document(reportId).get().get();
            if (report.exists() && report.get("reactions") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> reactions = (Map<String, Object>) report.get("reactions");
                counts.setUseful(toInt(reactions.get("useful")));
                counts.setInsightful(toInt(reactions.get("insightful")));
                counts.setVerify(toInt(reactions.get("verify")));
                counts.setComments(toInt(reactions.get("comments")));
            }

            // Get user's reaction
            if (userId != null) {
                String reactionId = reportId + "_" + userId;
                DocumentSnapshot userReaction = firestore.collection("reportReactions").document(reactionId).get().get();
                if (userReaction.exists()) {
                    counts.setUserReaction(userReaction.getString("reactionType"));
                }
            }

            return counts;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get reactions: {}", e.getMessage());
            return null;
        }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Long) return ((Long) val).intValue();
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }
}
