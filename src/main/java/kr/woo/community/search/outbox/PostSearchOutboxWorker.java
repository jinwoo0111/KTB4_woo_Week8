package kr.woo.community.search.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.search.indexing-enabled",
        havingValue = "true"
)
public class PostSearchOutboxWorker {

    private final PostSearchOutboxProcessor processor;
    private final String workerId = "post-search-" + UUID.randomUUID();

    public PostSearchOutboxWorker(PostSearchOutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.search.outbox.poll-delay:1s}")
    public void poll() {
        try {
            processor.processBatch(workerId);
        } catch (RuntimeException e) {
            log.error("Post search outbox polling failed", e);
        }
    }
}
