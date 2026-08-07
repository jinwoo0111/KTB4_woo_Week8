package kr.woo.community.search.outbox;

public record ClaimedPostSearchOutboxEvent(
        long eventId,
        long aggregateId,
        PostSearchOutboxEventType eventType,
        int payloadVersion,
        String payload,
        int attemptCount
) {

    public static ClaimedPostSearchOutboxEvent from(PostSearchOutboxEvent event) {
        return new ClaimedPostSearchOutboxEvent(
                event.getId(),
                event.getAggregateId(),
                event.getEventType(),
                event.getPayloadVersion(),
                event.getPayload(),
                event.getAttemptCount()
        );
    }
}
