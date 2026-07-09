package kr.woo.community.controller;


import kr.woo.community.common.ApiResponse;
import kr.woo.community.service.PostService;
import kr.woo.community.security.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequiredArgsConstructor
public class LikeController {

    private final PostService postService;
    // Post / posts/{postId}/likes - 좋아요 생성
    @PostMapping("/posts/{postId}/likes")
    public ResponseEntity<ApiResponse<Void>> createLike (
            @PathVariable Long postId,
            @AuthenticationPrincipal CustomUserDetails loginUser
    ) {
        postService.createLike(postId, loginUser.getId());
        ApiResponse<Void> response = new ApiResponse<>(
                "like_create_success",
                null
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }

    // Delete /posts/{postId}/likes - 좋아요 삭제
    @DeleteMapping("/posts/{postId}/likes")
    public ResponseEntity<ApiResponse<Void>> deleteLike (
            @PathVariable Long postId,
            @AuthenticationPrincipal CustomUserDetails loginUser
    ) {
        postService.deleteLike(postId, loginUser.getId());
        ApiResponse<Void> response = new ApiResponse<>(
                "post_like_delete_success",
                null
        );
        return ResponseEntity.ok(response);
    }
}

