package kr.woo.community.controller;

import jakarta.validation.Valid;
import kr.woo.community.common.ApiResponse;
import kr.woo.community.dto.*;
import kr.woo.community.service.PostService;
import kr.woo.community.security.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequiredArgsConstructor
public class PostController {
    private final PostService postService;

    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<PostListResponse>> getPosts(
            @RequestParam(required = false) Long cursor, // URL에서 cursor 값(필수X)
            @RequestParam(defaultValue = "10") int size // URL에서 size (기본값 10)
    ) {
        PostListResponse postListResponse = postService.getPosts(cursor, size);

        ApiResponse<PostListResponse> response = new ApiResponse<>(
                "posts_success",
                postListResponse
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPostDetail(
            @PathVariable Long postId
            @AuthenticationPrincipal CustomUserDetails loginUser
    ) {
        Long loginUserId = loginUser == null
                ? null
                : loginUser.getId();

        PostDetailResponse postDetailResponse = postService.getPostDetail(postId, loginUserId);
        ApiResponse<PostDetailResponse> response = new ApiResponse<>(
                "post_detail_success",
                postDetailResponse
        );
        return ResponseEntity.ok(response);
    }

    // Post/posts - 게시글 생성
    // 게시글 생성 요청을 받아 새 게시글 저장하고 201 Created 응답 반환
    @PostMapping("/posts")
    public ResponseEntity<ApiResponse<PostCreateResponse>> createPost(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @Valid @RequestBody PostCreateRequest request
    ) {
        PostCreateResponse postCreateResponse = postService.createPost(
                loginUser.getId(),
                request);
        ApiResponse<PostCreateResponse> response = new ApiResponse<>(
                "post_create_success",
                postCreateResponse
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }

    // Patch/posts/{postId} - 게시글 수정
    // 게시글 수정 요청을 받아 postId에 해당하는 게시글을 수정하고 200 반환
    @PatchMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostUpdateResponse>> updatePost (
            @PathVariable Long postId,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @Valid @RequestBody PostUpdateRequest request
    ) {
        PostUpdateResponse updateResponse = postService.updatePost(postId, loginUser.getId(), request);
        ApiResponse<PostUpdateResponse> response = new ApiResponse<>(
                "post_update_success",
                updateResponse
        );
        return ResponseEntity.ok(response);
    }

    // DELETE /posts/{postId} - 게시글 삭제
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<Void>> deletePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal CustomUserDetails loginUser
    ) {
        postService.deletePost(postId, loginUser.getId());
        ApiResponse<Void> response = new ApiResponse<>(
                "post_delete_success",
                null
        );
        return ResponseEntity.ok(response);
    }
}
