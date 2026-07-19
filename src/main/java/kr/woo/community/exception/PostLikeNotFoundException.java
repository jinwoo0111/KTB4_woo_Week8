package kr.woo.community.exception;

public class PostLikeNotFoundException extends RuntimeException {

    public PostLikeNotFoundException() {
        super("post_like_not_found");
    }
}
