package kr.woo.community.service;

import kr.woo.community.dto.*;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.PostLike;
import kr.woo.community.entity.User;
import kr.woo.community.entity.Comment;
import kr.woo.community.exception.InvalidPaginationParameterException;
import kr.woo.community.exception.PostNotFoundException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.PostLikeRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final PostLikeRepository postLikeRepository;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_PAGE_SIZE = 10;

    public Post findById(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new PostNotFoundException());
        if(post.isDeleted()) {
            throw new PostNotFoundException();
        }
        return post;
    }

    // GET /posts?cursor=16&size=10
    public PostListResponse getPosts(Long cursor, int size){
        // cursor 나 size 오류 예외
        if(size <= 0 || size > MAX_PAGE_SIZE || (cursor != null && cursor <= 0)){
            throw new InvalidPaginationParameterException();
        }

        // 조건에 맞는 게시글 size + 1 개 조회
        List<Post> posts = postRepository.findPostsByCursor(
                cursor,
                PageRequest.of(0, size + 1)
        );

        // 응답 가능한 게시글 수가 요청 size 보다 크면 다음 페이지가 존재
        boolean hasNext = posts.size() > size;

        // 실제 응답할 게시글만 pagePosts에 담음
        List<Post> pagePosts = new ArrayList<>();

        int limit = Math.min(size, posts.size());

        for(int i=0;i<limit;i++) {
            pagePosts.add(posts.get(i));
        }

        List<PostSummaryResponse> postResponses = new ArrayList<>();

        for (Post post : pagePosts) {
            postResponses.add(new PostSummaryResponse(
                    post.getId(),
                    post.getTitle(),
                    post.getCreatedAt().format(FORMATTER),
                    post.getLikeCount(),
                    post.getCommentCount(),
                    post.getViewCount(),
                    post.getAuthor().getNickname(),
                    post.getContent(),
                    post.getContentImage(),
                    post.getAuthor().getProfileImage()
            ));
        }
        Long nextCursor = null;
        if(hasNext && !pagePosts.isEmpty()) {
            nextCursor = pagePosts.get(pagePosts.size() -1).getId();
        }

        return new PostListResponse(
                postResponses,
                pagePosts.size(),
                hasNext,
                nextCursor
        );
    }

    // 게시글 상세 조회
    @Transactional
    public PostDetailResponse getPostDetail(Long postId, Long loginUserId){
        Post post = findById(postId);

        post.increaseViewCount();

        List<Comment> comments = commentRepository.findByPost_IdAndDeletedAtIsNullOrderByIdAsc(postId);
        List<CommentResponse> commentResponses = new ArrayList<>();

        for(Comment comment : comments){
            commentResponses.add(new CommentResponse(
                    comment.getId(),
                    comment.getAuthor().getId(),
                    comment.getAuthor().getNickname(),
                    comment.getAuthor().getProfileImage(),
                    comment.getCreatedAt().format(FORMATTER),
                    comment.getContent()
            ));
        }

        boolean likedByMe = false;

        if(loginUserId != null) {
            likedByMe = postLikeRepository.existsByPost_IdAndUser_Id(postId, loginUserId);
        }

        return new PostDetailResponse(
                post.getId(),
                post.getTitle(),
                post.getCreatedAt().format(FORMATTER),
                post.getAuthor().getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getProfileImage(),
                post.getContent(),
                post.getContentImage(),
                post.getLikeCount(),
                likedByMe,
                post.getCommentCount(),
                post.getViewCount(),
                commentResponses
        );
    }

    // 게시글 추가
    // 게시글 생성 요청 DTO를 받아 Repository에 저장하고,
    // 저장된 Post 도메인을 게시글 생성 응답 DTO로 변환해 반환
    @Transactional
    public PostCreateResponse createPost(Long loginUserId, PostCreateRequest request) {
        User author = userRepository.findById(loginUserId).orElseThrow(() -> new UserNotFoundException());
        if(author.isDeleted()) {
            throw new UserNotFoundException();
        }
        Post post = new Post(request.getTitle(),
                request.getContent(),
                request.getContentImage(),
                author
        );

        postRepository.save(post);
        return new PostCreateResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getContentImage(),
                post.getAuthor().getNickname()
        );
    }

    // 게시글 수정 처리
    // 게시글을 조회한 뒤 요청에 포함된 필드만 수정하고 응답 DTO로 반환
    @Transactional
    public PostUpdateResponse updatePost(Long postId, Long loginUserId,PostUpdateRequest request) {

        Post post = findById(postId);

        if (!post.getAuthor().getId().equals(loginUserId)) {
            throw new AccessDeniedException("게시글 작성자만 수정할 수 있습니다.");
        }

        if(request.getTitle() != null) {
            if(request.getTitle().isBlank()) {
                throw new IllegalArgumentException("title_blank");
            }
            post.changeTitle(request.getTitle());
        }

        if(request.getContent() != null) {
            if(request.getContent().isBlank()) {
                throw new IllegalArgumentException("content_blank");
            }
            post.changeContent(request.getContent());
        }

        if(request.getContentImage() != null) {
            post.changeContentImage(request.getContentImage());
        }

        return new PostUpdateResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getContentImage()
        );
    }

    // 게시글 삭제
    @Transactional
    public void deletePost(Long postId, Long loginUserId) {

        Post post = findById(postId);

        if (!post.getAuthor().getId().equals(loginUserId)) {
            throw new AccessDeniedException("게시글 작성자만 수정할 수 있습니다.");
        }

        List<Comment> comments = commentRepository.findByPost_IdAndDeletedAtIsNullOrderByIdAsc(postId);
        for(Comment comment : comments) {
            comment.softDelete();
        }
        post.softDelete();
    }

    // 좋아요 증가
    @Transactional
    public void createLike(Long postId, Long userId) {
        Post post = findById(postId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        if(user.isDeleted()){
            throw new UserNotFoundException();
        }

        if(postLikeRepository.existsByPost_IdAndUser_Id(postId, userId)) {
            throw new IllegalArgumentException("post_like_not_found");
        }

        PostLike postLike = new PostLike(post, user);
        postLikeRepository.save(postLike);

        post.increaseLikeCount();
    }

    // 좋아요 삭제
    @Transactional
    public void deleteLike(Long postId, Long userId) {
        Post post = findById(postId);

        User user = userRepository.findById(userId)
                        .orElseThrow(() -> new UserNotFoundException());
        if(user.isDeleted()) {
            throw new UserNotFoundException();
        }

        PostLike postLike = postLikeRepository.findByPost_IdAndUser_Id(postId, userId)
                        .orElseThrow(() -> new IllegalArgumentException("post_like_not_found"));
        postLikeRepository.delete(postLike);

        post.decreaseLikeCount();
    }
}
