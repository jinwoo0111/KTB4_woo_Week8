package kr.woo.community.exception;

public class InvalidSearchSortException extends RuntimeException {

    public InvalidSearchSortException() {
        super("invalid_search_sort");
    }
}
