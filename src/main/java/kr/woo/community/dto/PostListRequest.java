package kr.woo.community.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PostListRequest {
    private String keyword;
    private String scope;
    private Long cursor;
    private int size = 10;
}
