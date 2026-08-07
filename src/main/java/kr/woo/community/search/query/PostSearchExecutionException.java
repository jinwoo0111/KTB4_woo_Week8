package kr.woo.community.search.query;

public class PostSearchExecutionException extends RuntimeException {

    public PostSearchExecutionException(String message) {
        super(message);
    }

    public PostSearchExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
