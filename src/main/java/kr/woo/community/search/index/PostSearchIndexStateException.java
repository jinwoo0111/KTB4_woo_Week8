package kr.woo.community.search.index;

public class PostSearchIndexStateException extends IllegalStateException {

    public PostSearchIndexStateException(String message) {
        super(message);
    }

    public PostSearchIndexStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
