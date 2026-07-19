package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.dto.PostUpdateRequest;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.InvalidRequestException;
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
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
