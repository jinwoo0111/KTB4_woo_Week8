package kr.woo.community.search.query;

public class InvalidPostSearchCursorException extends IllegalArgumentException {

    public InvalidPostSearchCursorException(String message) {
        super(message);
    }

    public InvalidPostSearchCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
