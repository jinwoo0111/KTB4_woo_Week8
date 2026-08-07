package kr.woo.community.search.outbox;

public interface PostSearchOutboxIndexer {

    PostSearchIndexingResult apply(ClaimedPostSearchOutboxEvent event);
}
