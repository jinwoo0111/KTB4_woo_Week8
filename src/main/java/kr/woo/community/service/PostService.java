package kr.woo.community.service;

import kr.woo.community.dto.*;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.PostLike;
import kr.woo.community.entity.User;
import kr.woo.community.entity.Comment;
import kr.woo.community.exception.InvalidPaginationParameterException;
import kr.woo.community.exception.InvalidSearchKeywordException;
import kr.woo.community.exception.InvalidSearchScopeException;
import kr.woo.community.exception.InvalidSearchSortException;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.exception.PostLikeNotFoundException;
import kr.woo.community.exception.PostNotFoundException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.PostLikeRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.PostFtsSearchRepository;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.search.query.PostSearchCandidate;
import kr.woo.community.search.query.PostSearchCriteria;
import kr.woo.community.search.query.PostSearchGateway;
import kr.woo.community.search.query.PostSearchPage;
import kr.woo.community.search.outbox.PostSearchOutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository postRepository;
    private final PostFtsSearchRepository postFtsSearchRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final PostLikeRepository postLikeRepository;
    private final FileStorageService fileStorageService;
    private final PostSearchGateway postSearchGateway;
    private final PostSearchOutboxWriter postSearchOutboxWriter;

    @Value("${app.search.mode:like}")
    private String searchMode = "like";

    @Value("${app.search.backend:postgres}")
    private String searchBackend = "postgres";

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_PAGE_SIZE = 10;
    private static final int MIN_SEARCH_KEYWORD_LENGTH = 2;
    private static final int MAX_SEARCH_KEYWORD_LENGTH = 100;

    private enum SearchScope {
        ALL,
        TITLE,
        CONTENT
    }

    private enum SearchSort {
        TIME,
        RELEVANCE
    }

    public Post findById(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new PostNotFoundException());
        if(post.isDeleted()) {
            throw new PostNotFoundException();
        }
        return post;
    }

    private String normalizeKeyword(String keyword) {
        String normalizedKeyword = keyword.strip();

        if (normalizedKeyword.isEmpty()
                || normalizedKeyword.length() < MIN_SEARCH_KEYWORD_LENGTH
                || normalizedKeyword.length() > MAX_SEARCH_KEYWORD_LENGTH) {
            throw new InvalidSearchKeywordException();
        }

        return normalizedKeyword.toLowerCase(Locale.ROOT);
    }

    private String escapeLikeKeyword(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchScope resolveSearchScope(String scope) {
        if (scope == null) {
            return SearchScope.ALL;
        }

        return switch (scope) {
            case "all" -> SearchScope.ALL;
            case "title" -> SearchScope.TITLE;
            case "content" -> SearchScope.CONTENT;
            default -> throw new InvalidSearchScopeException();
        };
    }

    private SearchSort resolveSearchSort(String sort) {
        if (sort == null) {
            return SearchSort.TIME;
        }

        return switch (sort) {
            case "time" -> SearchSort.TIME;
            case "relevance" -> SearchSort.RELEVANCE;
            default -> throw new InvalidSearchSortException();
        };
    }

    private Long resolveLegacyCursor(String cursor) {
        if (cursor == null) {
            return null;
        }

        try {
            Long resolvedCursor = Long.valueOf(cursor.strip());
            if (resolvedCursor <= 0) {
                throw new InvalidPaginationParameterException();
            }
            return resolvedCursor;
        } catch (NumberFormatException e) {
            throw new InvalidPaginationParameterException();
        }
    }

    public PostListResponse getPosts(PostListRequest request) {
        int size = request.getSize();

        if(size <= 0 || size > MAX_PAGE_SIZE){
            throw new InvalidPaginationParameterException();
        }

        String keyword = request.getKeyword();
        String scope = request.getScope();
        SearchSort searchSort = resolveSearchSort(request.getSort());

        if (keyword == null) {
            if (scope != null) {
                throw new InvalidSearchKeywordException();
            }
            if (searchSort == SearchSort.RELEVANCE) {
                throw new InvalidSearchSortException();
            }

            Long cursor = resolveLegacyCursor(request.getCursor());
            List<Post> posts = postRepository.findPostsByCursor(
                    cursor,
                    PageRequest.of(0, size + 1)
            );
            return createPostListResponse(posts, size, null);
        }

        String normalizedKeyword = normalizeKeyword(keyword);
        SearchScope searchScope = resolveSearchScope(scope);

        if ("elasticsearch".equalsIgnoreCase(searchBackend)) {
            return searchPostsWithElasticsearch(
                    normalizedKeyword,
                    searchScope,
                    searchSort,
                    request.getCursor(),
                    size
            );
        }

        Long cursor = resolveLegacyCursor(request.getCursor());
        List<Post> posts;
        if ("fts".equalsIgnoreCase(searchMode)) {
            posts = searchPostsWithFts(normalizedKeyword, searchScope, cursor, size + 1);
        } else {
            posts = searchPostsWithLike(
                    escapeLikeKeyword(normalizedKeyword),
                    searchScope,
                    cursor,
                    PageRequest.of(0, size + 1)
            );
        }

        PostSearchMetadataResponse searchMetadata = new PostSearchMetadataResponse(
                searchSort.name().toLowerCase(Locale.ROOT),
                "time",
                "postgres",
                false
        );

        return createPostListResponse(posts, size, searchMetadata);
    }

    private PostListResponse searchPostsWithElasticsearch(
            String keyword,
            SearchScope searchScope,
            SearchSort searchSort,
            String cursor,
            int size
    ) {
        PostSearchCriteria criteria = new PostSearchCriteria(
                keyword,
                kr.woo.community.search.query.PostSearchScope.valueOf(searchScope.name()),
                kr.woo.community.search.query.PostSearchSort.valueOf(searchSort.name()),
                size
        );
        PostSearchPage page = postSearchGateway.searchPage(criteria, cursor);
        List<Post> posts = hydrateActivePosts(page.candidates());
        String effectiveSort = searchSort.name().toLowerCase(Locale.ROOT);

        return new PostListResponse(
                toPostSummaryResponses(posts),
                posts.size(),
                page.hasNext(),
                page.nextCursor(),
                new PostSearchMetadataResponse(
                        effectiveSort,
                        effectiveSort,
                        "elasticsearch",
                        false
                )
        );
    }

    private List<Post> hydrateActivePosts(List<PostSearchCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = candidates.stream()
                .map(PostSearchCandidate::postId)
                .toList();
        Map<Long, Post> activePostsById = new LinkedHashMap<>();
        for (Post post : postRepository.findAllActiveByIdsWithAuthor(postIds)) {
            activePostsById.put(post.getId(), post);
        }

        List<Post> orderedPosts = new ArrayList<>();
        for (PostSearchCandidate candidate : candidates) {
            Post post = activePostsById.get(candidate.postId());
            if (post != null) {
                orderedPosts.add(post);
            }
        }
        return List.copyOf(orderedPosts);
    }

    private List<Post> searchPostsWithLike(
            String keyword,
            SearchScope searchScope,
            Long cursor,
            PageRequest pageable
    ) {
        return switch (searchScope) {
            case ALL -> postRepository.searchPostsByTitleOrContent(keyword, cursor, pageable);
            case TITLE -> postRepository.searchPostsByTitle(keyword, cursor, pageable);
            case CONTENT -> postRepository.searchPostsByContent(keyword, cursor, pageable);
        };
    }

    private List<Post> searchPostsWithFts(
            String keyword,
            SearchScope searchScope,
            Long cursor,
            int limit
    ) {
        return switch (searchScope) {
            case ALL -> postFtsSearchRepository.searchByTitleOrContent(keyword, cursor, limit);
            case TITLE -> postFtsSearchRepository.searchByTitle(keyword, cursor, limit);
            case CONTENT -> postFtsSearchRepository.searchByContent(keyword, cursor, limit);
        };
    }

    private PostListResponse createPostListResponse(
            List<Post> posts,
            int size,
            PostSearchMetadataResponse searchMetadata
    ) {
        // 응답 가능한 게시글 수가 요청 size 보다 크면 다음 페이지가 존재
        boolean hasNext = posts.size() > size;

        // 실제 응답할 게시글만 pagePosts에 담음
        List<Post> pagePosts = new ArrayList<>();

        int limit = Math.min(size, posts.size());

        for(int i=0;i<limit;i++) {
            pagePosts.add(posts.get(i));
        }

        List<PostSummaryResponse> postResponses = toPostSummaryResponses(pagePosts);
        Long nextCursor = null;
        if(hasNext && !pagePosts.isEmpty()) {
            nextCursor = pagePosts.get(pagePosts.size() -1).getId();
        }

        return new PostListResponse(
                postResponses,
                pagePosts.size(),
                hasNext,
                nextCursor,
                searchMetadata
        );
    }

    private List<PostSummaryResponse> toPostSummaryResponses(List<Post> posts) {
        List<PostSummaryResponse> postResponses = new ArrayList<>();
        for (Post post : posts) {
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
        return List.copyOf(postResponses);
    }

    // 게시글 상세 조회
    public PostDetailResponse getPostDetail(Long postId, Long loginUserId){
        Post post = findById(postId);

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

    // 게시글 상세 조회와 분리된 조회수 증가
    @Transactional
    public PostViewResponse increaseViewCount(Long postId) {
        int updatedRowCount = postRepository.increaseViewCount(postId);

        if (updatedRowCount == 0) {
            throw new PostNotFoundException();
        }

        Post post = findById(postId);
        return new PostViewResponse(post.getViewCount());
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
        String contentImage = request.getContentImage();
        if (contentImage != null && contentImage.isBlank()) {
            contentImage = null;
        }

        Post post = new Post(request.getTitle(),
                request.getContent(),
                contentImage,
                author
        );

        postRepository.save(post);
        postRepository.flush();
        postSearchOutboxWriter.recordUpsert(post);
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

        boolean searchProjectionChanged = false;

        if(request.getTitle() != null) {
            if(request.getTitle().isBlank()) {
                throw new InvalidRequestException("title_blank");
            }
            if (!request.getTitle().equals(post.getTitle())) {
                post.changeTitle(request.getTitle());
                searchProjectionChanged = true;
            }
        }

        if(request.getContent() != null) {
            if(request.getContent().isBlank()) {
                throw new InvalidRequestException("content_blank");
            }
            if (!request.getContent().equals(post.getContent())) {
                post.changeContent(request.getContent());
                searchProjectionChanged = true;
            }
        }

        updateContentImage(post, request);

        if (searchProjectionChanged) {
            postRepository.flush();
            postSearchOutboxWriter.recordUpsert(post);
        }

        return new PostUpdateResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getContentImage()
        );
    }

    private void updateContentImage(Post post, PostUpdateRequest request) {
        String newImagePath = request.getContentImage();
        boolean legacyRemoveRequest = newImagePath != null && newImagePath.isBlank();
        boolean removeRequested = request.isRemoveContentImage() || legacyRemoveRequest;

        if (request.isRemoveContentImage() && newImagePath != null && !newImagePath.isBlank()) {
            throw new InvalidRequestException("content_image_update_conflict");
        }

        String oldImagePath = post.getContentImage();

        if (removeRequested) {
            post.changeContentImage(null);
            fileStorageService.deleteImageAfterCommit(oldImagePath);
            return;
        }

        if (newImagePath != null && !newImagePath.equals(oldImagePath)) {
            post.changeContentImage(newImagePath);
            fileStorageService.deleteImageAfterCommit(oldImagePath);
        }
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
        postRepository.flush();
        postSearchOutboxWriter.recordDelete(post.getId());
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
            throw new ConflictException("post_like_already_exists");
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
                        .orElseThrow(PostLikeNotFoundException::new);
        postLikeRepository.delete(postLike);

        post.decreaseLikeCount();
    }
}
