package kr.woo.community.search.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class PostSearchDocument {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_CONTENT_LENGTH = 32_000;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @JsonProperty("post_id")
    private final Long postId;

    private final String title;
    private final String content;

    @JsonProperty("created_at")
    private final String createdAt;

    @JsonProperty("updated_at")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String updatedAt;

    public PostSearchDocument(
            Long postId,
            String title,
            String content,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.postId = requirePositivePostId(postId);
        this.title = requireTextWithinLength(title, "title", MAX_TITLE_LENGTH);
        this.content = requireTextWithinLength(content, "content", MAX_CONTENT_LENGTH);
        this.createdAt = formatTimestamp(Objects.requireNonNull(createdAt, "createdAt"));
        this.updatedAt = updatedAt == null ? null : formatTimestamp(updatedAt);
    }

    @JsonIgnore
    public String getDocumentId() {
        return postId.toString();
    }

    public Long getPostId() {
        return postId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    private static Long requirePositivePostId(Long postId) {
        Objects.requireNonNull(postId, "postId");
        if (postId <= 0) {
            throw new IllegalArgumentException("postId must be positive");
        }
        return postId;
    }

    private static String requireTextWithinLength(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and at most " + maxLength + " characters"
            );
        }
        return value;
    }

    private static String formatTimestamp(LocalDateTime timestamp) {
        return TIMESTAMP_FORMATTER.format(timestamp);
    }
}
