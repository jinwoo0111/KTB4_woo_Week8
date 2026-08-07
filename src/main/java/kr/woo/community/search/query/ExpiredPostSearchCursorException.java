package kr.woo.community.search.query;

public class ExpiredPostSearchCursorException extends InvalidPostSearchCursorException {

    public ExpiredPostSearchCursorException() {
        super("Post search cursor has expired");
    }
}
