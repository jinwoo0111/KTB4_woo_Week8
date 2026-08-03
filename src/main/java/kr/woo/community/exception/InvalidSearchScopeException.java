package kr.woo.community.exception;

public class InvalidSearchScopeException extends RuntimeException {

    public InvalidSearchScopeException() {
        super("invalid_search_scope");
    }
}
