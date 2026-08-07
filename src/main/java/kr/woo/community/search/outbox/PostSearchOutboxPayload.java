package kr.woo.community.search.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import kr.woo.community.search.document.PostSearchDocument;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostSearchOutboxPayload(
        @JsonProperty("post_id") Long postId,
        String title,
        String content,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {

    public static PostSearchOutboxPayload upsert(PostSearchDocument document) {
        return new PostSearchOutboxPayload(
                document.getPostId(),
                document.getTitle(),
                document.getContent(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    public static PostSearchOutboxPayload delete(Long postId) {
        if (postId == null || postId <= 0) {
            throw new IllegalArgumentException("postId must be positive");
        }
        return new PostSearchOutboxPayload(postId, null, null, null, null);
    }
}
