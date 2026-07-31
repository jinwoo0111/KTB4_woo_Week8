package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.dto.PostListRequest;
import kr.woo.community.dto.PostListResponse;
import kr.woo.community.dto.PostUpdateRequest;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.InvalidPaginationParameterException;
import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.exception.InvalidSearchKeywordException;
import kr.woo.community.exception.InvalidSearchScopeException;
import kr.woo.community.exception.PostNotFoundException;
import kr.woo.community.exception.PostLikeNotFoundException;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.PostLikeRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.PostService;
import kr.woo.community.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PostLikeRepository postLikeRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private PostService postService;

    @Test
    @DisplayName("검색어가 없으면 다음 페이지 확인을 위해 기존 목록을 한 개 더 조회한다")
    void getPostsWithoutKeywordUsesDefaultListQuery() {
        // given
        PostListRequest request = new PostListRequest();
        when(postRepository.findPostsByCursor(isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        // when
        PostListResponse response = postService.getPosts(request);

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findPostsByCursor(isNull(), pageableCaptor.capture());
        assertEquals(11, pageableCaptor.getValue().getPageSize());

        verify(postRepository, never()).searchPostsByTitleOrContent(any(), any(), any());
        verify(postRepository, never()).searchPostsByTitle(any(), any(), any());
        verify(postRepository, never()).searchPostsByContent(any(), any(), any());

        assertEquals(0, response.getCount());
        assertFalse(response.isHasNext());
        assertNull(response.getNextCursor());
    }

    @Test
    @DisplayName("제목 검색은 검색어를 정규화하고 이스케이프해 제목 검색 쿼리를 호출한다")
    void getPostsWithTitleScopeNormalizesKeyword() {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword("  SPRING   _100%  ");
        request.setScope("title");
        request.setCursor(100L);
        request.setSize(5);

        when(postRepository.searchPostsByTitle(
                any(),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of());

        // when
        postService.getPosts(request);

        // then
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(postRepository).searchPostsByTitle(
                keywordCaptor.capture(),
                eq(100L),
                pageableCaptor.capture()
        );

        assertEquals("spring   \\_100\\%", keywordCaptor.getValue());
        assertEquals(6, pageableCaptor.getValue().getPageSize());

        verify(postRepository, never()).searchPostsByTitleOrContent(any(), any(), any());
        verify(postRepository, never()).searchPostsByContent(any(), any(), any());
        verify(postRepository, never()).findPostsByCursor(any(), any());
    }

    @Test
    @DisplayName("검색어의 역슬래시는 LIKE 일반 문자로 검색되도록 이스케이프한다")
    void getPostsEscapesBackslashInKeyword() {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword("C:\\Temp");
        request.setScope("title");

        when(postRepository.searchPostsByTitle(
                any(),
                isNull(),
                any(Pageable.class)
        )).thenReturn(List.of());

        // when
        postService.getPosts(request);

        // then
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(postRepository).searchPostsByTitle(
                keywordCaptor.capture(),
                isNull(),
                any(Pageable.class)
        );
        assertEquals("c:\\\\temp", keywordCaptor.getValue());
    }

    @ParameterizedTest
    @MethodSource("invalidSearchKeywords")
    @DisplayName("빈 검색어와 길이 조건을 위반한 검색어는 검색하지 않는다")
    void getPostsFailsWhenKeywordIsInvalid(String keyword) {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword(keyword);

        // when & then
        assertThrows(
                InvalidSearchKeywordException.class,
                () -> postService.getPosts(request)
        );
        verifyNoInteractions(postRepository);
    }

    private static Stream<String> invalidSearchKeywords() {
        return Stream.of(
                "",
                "   ",
                "가",
                "가".repeat(101)
        );
    }

    @Test
    @DisplayName("검색어 없이 검색 범위만 전달하면 검색어 오류가 발생한다")
    void getPostsFailsWhenScopeExistsWithoutKeyword() {
        // given
        PostListRequest request = new PostListRequest();
        request.setScope("title");

        // when & then
        assertThrows(
                InvalidSearchKeywordException.class,
                () -> postService.getPosts(request)
        );
        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("허용되지 않은 검색 범위는 검색 범위 오류가 발생한다")
    void getPostsFailsWhenScopeIsInvalid() {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword("스프링");
        request.setScope("author");

        // when & then
        assertThrows(
                InvalidSearchScopeException.class,
                () -> postService.getPosts(request)
        );
        verifyNoInteractions(postRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "all")
    @DisplayName("검색 범위를 생략하거나 all로 지정하면 제목과 내용 전체를 검색한다")
    void getPostsUsesTitleOrContentQueryForAllScope(String scope) {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword("스프링");
        request.setScope(scope);

        when(postRepository.searchPostsByTitleOrContent(
                eq("스프링"),
                isNull(),
                any(Pageable.class)
        )).thenReturn(List.of());

        // when
        postService.getPosts(request);

        // then
        verify(postRepository).searchPostsByTitleOrContent(
                eq("스프링"),
                isNull(),
                any(Pageable.class)
        );
        verify(postRepository, never()).searchPostsByTitle(any(), any(), any());
        verify(postRepository, never()).searchPostsByContent(any(), any(), any());
        verify(postRepository, never()).findPostsByCursor(any(), any());
    }

    @Test
    @DisplayName("content 검색 범위는 내용 검색 쿼리를 호출한다")
    void getPostsUsesContentQueryForContentScope() {
        // given
        PostListRequest request = new PostListRequest();
        request.setKeyword("스프링");
        request.setScope("content");

        when(postRepository.searchPostsByContent(
                eq("스프링"),
                isNull(),
                any(Pageable.class)
        )).thenReturn(List.of());

        // when
        postService.getPosts(request);

        // then
        verify(postRepository).searchPostsByContent(
                eq("스프링"),
                isNull(),
                any(Pageable.class)
        );
        verify(postRepository, never()).searchPostsByTitleOrContent(any(), any(), any());
        verify(postRepository, never()).searchPostsByTitle(any(), any(), any());
        verify(postRepository, never()).findPostsByCursor(any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidPaginationRequests")
    @DisplayName("페이지 크기 또는 커서가 허용 범위를 벗어나면 조회하지 않는다")
    void getPostsFailsWhenPaginationIsInvalid(Long cursor, int size) {
        // given
        PostListRequest request = new PostListRequest();
        request.setCursor(cursor);
        request.setSize(size);

        // when & then
        assertThrows(
                InvalidPaginationParameterException.class,
                () -> postService.getPosts(request)
        );
        verifyNoInteractions(postRepository);
    }

    private static Stream<Arguments> invalidPaginationRequests() {
        return Stream.of(
                Arguments.of(null, 0),
                Arguments.of(null, 11),
                Arguments.of(0L, 10),
                Arguments.of(-1L, 10)
        );
    }

    @Test
    @DisplayName("추가 조회 결과가 있으면 응답의 마지막 게시글을 다음 커서로 사용한다")
    void getPostsBuildsNextCursorFromLastResponsePost() {
        // given
        PostListRequest request = new PostListRequest();
        request.setSize(2);

        User author = mock(User.class);
        Post post30 = mock(Post.class);
        Post post20 = mock(Post.class);
        Post lookaheadPost10 = mock(Post.class);

        when(author.getNickname()).thenReturn("작성자");
        when(post30.getId()).thenReturn(30L);
        when(post30.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(post30.getAuthor()).thenReturn(author);
        when(post20.getId()).thenReturn(20L);
        when(post20.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(post20.getAuthor()).thenReturn(author);

        when(postRepository.findPostsByCursor(isNull(), any(Pageable.class)))
                .thenReturn(List.of(post30, post20, lookaheadPost10));

        // when
        PostListResponse response = postService.getPosts(request);

        // then
        assertEquals(2, response.getPosts().size());
        assertEquals(30L, response.getPosts().get(0).getPostId());
        assertEquals(20L, response.getPosts().get(1).getPostId());
        assertEquals(2, response.getCount());
        assertTrue(response.isHasNext());
        assertEquals(20L, response.getNextCursor());
    }

    @Test
    @DisplayName("조회 결과가 요청 크기와 같으면 다음 페이지와 다음 커서가 없다")
    void getPostsReturnsNoNextCursorForLastPage() {
        // given
        PostListRequest request = new PostListRequest();
        request.setSize(2);

        User author = mock(User.class);
        Post post30 = mock(Post.class);
        Post post20 = mock(Post.class);

        when(author.getNickname()).thenReturn("작성자");
        when(post30.getId()).thenReturn(30L);
        when(post30.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(post30.getAuthor()).thenReturn(author);
        when(post20.getId()).thenReturn(20L);
        when(post20.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(post20.getAuthor()).thenReturn(author);

        when(postRepository.findPostsByCursor(isNull(), any(Pageable.class)))
                .thenReturn(List.of(post30, post20));

        // when
        PostListResponse response = postService.getPosts(request);

        // then
        assertEquals(2, response.getPosts().size());
        assertEquals(2, response.getCount());
        assertFalse(response.isHasNext());
        assertNull(response.getNextCursor());
    }

    @Test
    @DisplayName("이미 좋아요한 게시글에 다시 좋아요하면 충돌 예외가 발생한다")
    void createLikeFailWhenLikeAlreadyExists() {
        Long postId = 1L;
        Long userId = 2L;
        User user = new User("test@test.com", "password", "nickname", null);
        Post post = new Post("title", "content", null, user);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postLikeRepository.existsByPost_IdAndUser_Id(postId, userId)).thenReturn(true);

        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> postService.createLike(postId, userId)
        );

        assertEquals("post_like_already_exists", exception.getMessage());
        verify(postLikeRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("존재하지 않는 좋아요를 삭제하면 찾을 수 없음 예외가 발생한다")
    void deleteLikeFailWhenLikeDoesNotExist() {
        Long postId = 1L;
        Long userId = 2L;
        User user = new User("test@test.com", "password", "nickname", null);
        Post post = new Post("title", "content", null, user);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postLikeRepository.findByPost_IdAndUser_Id(postId, userId))
                .thenReturn(Optional.empty());

        PostLikeNotFoundException exception = assertThrows(
                PostLikeNotFoundException.class,
                () -> postService.deleteLike(postId, userId)
        );

        assertEquals("post_like_not_found", exception.getMessage());
    }

    @Test
    @DisplayName("게시글 수정 제목이 공백이면 유효하지 않은 요청 예외가 발생한다")
    void updatePostFailWhenTitleIsBlank() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(request.getTitle()).thenReturn("   ");

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> postService.updatePost(postId, userId, request)
        );

        assertEquals("title_blank", exception.getMessage());
        verify(post, never()).changeTitle(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("게시글 수정 내용이 공백이면 유효하지 않은 요청 예외가 발생한다")
    void updatePostFailWhenContentIsBlank() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(request.getContent()).thenReturn("\t");

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> postService.updatePost(postId, userId, request)
        );

        assertEquals("content_blank", exception.getMessage());
        verify(post, never()).changeContent(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("게시글 기존 이미지를 명시적으로 제거한다")
    void updatePostRemovesExistingImage() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(post.getContentImage()).thenReturn("/uploads/post/old.png");
        when(request.isRemoveContentImage()).thenReturn(true);

        postService.updatePost(postId, userId, request);

        verify(post).changeContentImage(null);
        verify(fileStorageService).deleteImageAfterCommit("/uploads/post/old.png");
    }

    @Test
    @DisplayName("게시글 이미지를 교체하면 기존 이미지를 커밋 후 삭제하도록 요청한다")
    void updatePostReplacesExistingImage() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(post.getContentImage()).thenReturn("/uploads/post/old.png");
        when(request.getContentImage()).thenReturn("/uploads/post/new.png");

        postService.updatePost(postId, userId, request);

        verify(post).changeContentImage("/uploads/post/new.png");
        verify(fileStorageService).deleteImageAfterCommit("/uploads/post/old.png");
    }

    @Test
    @DisplayName("게시글 이미지 변경 값이 없으면 기존 이미지를 유지한다")
    void updatePostKeepsExistingImage() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(post.getContentImage()).thenReturn("/uploads/post/old.png");

        postService.updatePost(postId, userId, request);

        verify(post, never()).changeContentImage(org.mockito.ArgumentMatchers.any());
        verify(fileStorageService, never()).deleteImageAfterCommit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("게시글 이미지 교체와 제거를 동시에 요청하면 400 예외가 발생한다")
    void updatePostFailsWhenImageRequestConflicts() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(request.getContentImage()).thenReturn("/uploads/post/new.png");
        when(request.isRemoveContentImage()).thenReturn(true);

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> postService.updatePost(postId, userId, request)
        );

        assertEquals("content_image_update_conflict", exception.getMessage());
        verify(post, never()).changeContentImage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("기존 프론트엔드의 빈 문자열 이미지 제거 요청도 null로 저장한다")
    void updatePostSupportsLegacyEmptyImageRemoval() {
        Long postId = 1L;
        Long userId = 2L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        PostUpdateRequest request = mock(PostUpdateRequest.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        when(post.getContentImage()).thenReturn("/uploads/post/old.png");
        when(request.getContentImage()).thenReturn("");

        postService.updatePost(postId, userId, request);

        verify(post).changeContentImage(null);
        verify(fileStorageService).deleteImageAfterCommit("/uploads/post/old.png");
    }

    @Test
    @DisplayName("게시글 생성 시 빈 이미지 경로는 null로 저장한다")
    void createPostNormalizesBlankImagePath() {
        Long userId = 1L;
        User user = new User("test@test.com", "password", "nickname", null);
        kr.woo.community.dto.PostCreateRequest request =
                mock(kr.woo.community.dto.PostCreateRequest.class);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(request.getTitle()).thenReturn("title");
        when(request.getContent()).thenReturn("content");
        when(request.getContentImage()).thenReturn("   ");

        postService.createPost(userId, request);

        ArgumentCaptor<Post> postCaptor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(postCaptor.capture());
        assertNull(postCaptor.getValue().getContentImage());
    }

    @Test
    @DisplayName("게시글 상세 조회는 조회수를 증가시키지 않는다")
    void getPostDetailDoesNotIncreaseViewCount() {
        Long postId = 1L;
        Post post = mock(Post.class);
        User author = mock(User.class);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(author);
        when(post.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(commentRepository.findByPost_IdAndDeletedAtIsNullOrderByIdAsc(postId))
                .thenReturn(List.of());

        postService.getPostDetail(postId, null);

        verify(postRepository, never()).increaseViewCount(postId);
    }

    @Test
    @DisplayName("게시글 조회수를 원자적으로 증가시키고 증가된 값을 반환한다")
    void increaseViewCountSuccess() {
        Long postId = 1L;
        Post post = mock(Post.class);

        when(postRepository.increaseViewCount(postId)).thenReturn(1);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.getViewCount()).thenReturn(11);

        kr.woo.community.dto.PostViewResponse response =
                postService.increaseViewCount(postId);

        assertEquals(11, response.getViewCount());
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 게시글의 조회수는 증가시키지 않는다")
    void increaseViewCountFailsWhenPostDoesNotExist() {
        Long postId = 1L;
        when(postRepository.increaseViewCount(postId)).thenReturn(0);

        assertThrows(
                PostNotFoundException.class,
                () -> postService.increaseViewCount(postId)
        );

        verify(postRepository, never()).findById(postId);
    }
}
