package kr.woo.community.search.outbox;

import kr.woo.community.entity.Post;
import kr.woo.community.search.document.PostSearchDocument;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class PostSearchOutboxWriter {

    private final PostSearchOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PostSearchOutboxWriter(
            PostSearchOutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void recordUpsert(Post post) {
        PostSearchDocument document = new PostSearchDocument(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
        save(
                post.getId(),
                PostSearchOutboxEventType.UPSERT,
                PostSearchOutboxPayload.upsert(document)
        );
    }

    public void recordDelete(Long postId) {
        save(
                postId,
                PostSearchOutboxEventType.DELETE,
                PostSearchOutboxPayload.delete(postId)
        );
    }

    private void save(
            Long postId,
            PostSearchOutboxEventType eventType,
            PostSearchOutboxPayload payload
    ) {
        try {
            String serializedPayload = objectMapper.writeValueAsString(payload);
            outboxRepository.save(PostSearchOutboxEvent.pending(
                    postId,
                    eventType,
                    serializedPayload
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize post search outbox payload", e);
        }
    }
}
