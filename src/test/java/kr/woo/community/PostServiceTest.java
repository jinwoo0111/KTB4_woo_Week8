package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.PostLikeNotFoundException;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.PostLikeRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
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
}
