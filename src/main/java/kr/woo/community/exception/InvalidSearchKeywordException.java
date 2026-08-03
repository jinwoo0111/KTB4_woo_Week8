package kr.woo.community.exception;

public class InvalidSearchKeywordException extends RuntimeException {

    public InvalidSearchKeywordException() {
        super("invalid_search_keyword");
    }
}
