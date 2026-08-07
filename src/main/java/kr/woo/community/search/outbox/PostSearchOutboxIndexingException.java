package kr.woo.community.search.outbox;

public class PostSearchOutboxIndexingException extends RuntimeException {

    public PostSearchOutboxIndexingException(String message) {
        super(message);
    }

    public PostSearchOutboxIndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
