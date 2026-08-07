package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// 게시물 목록 조회 시, 각 게시물 요약본을 리스트로 하여 반환
public class PostListResponse {
    private List<PostSummaryResponse> posts;
    private int count;

    @JsonProperty("has_next")
    private boolean hasNext;

    @JsonProperty("next_cursor")
    private Object nextCursor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PostSearchMetadataResponse search;

    public PostListResponse(
            List<PostSummaryResponse> posts,
            int count,
            boolean hasNext,
            Object nextCursor
    ) {
        this(posts, count, hasNext, nextCursor, null);
    }

    public PostListResponse(
            List<PostSummaryResponse> posts,
            int count,
            boolean hasNext,
            Object nextCursor,
            PostSearchMetadataResponse search
    ) {
        this.posts = posts;
        this.count = count;
        this.hasNext = hasNext;
        this.nextCursor = nextCursor;
        this.search = search;
    }

    public List<PostSummaryResponse> getPosts() {
        return posts;
    }

    public int getCount() {
        return count;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public Object getNextCursor() {
        return nextCursor;
    }

    public PostSearchMetadataResponse getSearch() {
        return search;
    }
}
