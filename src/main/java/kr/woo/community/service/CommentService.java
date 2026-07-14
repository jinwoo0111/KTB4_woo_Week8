package kr.woo.community.service;

import kr.woo.community.entity.Comment;
import kr.woo.community.dto.CommentCreateRequest;
import kr.woo.community.dto.CommentCreateResponse;
import kr.woo.community.dto.CommentUpdateRequest;
import kr.woo.community.dto.CommentUpdateResponse;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.exception.CommentNotFoundException;
import kr.woo.community.exception.PostNotFoundException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

import java.time.format.DateTimeFormatter;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Comment findById(Long id) {
        Comment comment = commentRepository.findById(id)
                .orElseThrow(() -> new CommentNotFoundException());

        if(comment.isDeleted()) {
            throw new CommentNotFoundException();
        }
        return comment;
    }

    // 댓글 생성 - 게시글 존재 확인 후 댓글을 저장하고 게시글 댓글 수를 증가
    @Transactional
    public CommentCreateResponse createComment(Long postId, Long loginUserId, CommentCreateRequest request){
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException());

        if(post.isDeleted()) {
            throw new PostNotFoundException();
        }

        User author = userRepository.findById(loginUserId)
                .orElseThrow(() -> new UserNotFoundException());

        if (author.isDeleted()) {
            throw new UserNotFoundException();
        }

        Comment comment =  new Comment(
                post,
                author,
                request.getContent()
        );

        post.increaseCommentCount();

        commentRepository.save(comment);

        return new CommentCreateResponse(
                comment.getId(),
                comment.getAuthor().getId(),
                comment.getAuthor().getNickname(),
                comment.getCreatedAt().format(FORMATTER),
                comment.getContent()
        );
    }

    // 댓글 삭제 처리 - 게시글과 댓글 존재 여부를 확인한 뒤 댓글을 삭제하고 게시글 댓글 수 감소
    @Transactional
    public void deleteComment(Long postId, Long commentId, Long loginUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException());

        if (post.isDeleted()) {
            throw new PostNotFoundException();
        }

        Comment comment = findById(commentId);

        if (!comment.getPost().getId().equals(postId)) {
            throw new CommentNotFoundException();
        }

        if (!comment.getAuthor().getId().equals(loginUserId)) {
            throw new AccessDeniedException("댓글 작성자만 삭제할 수 있습니다.");
        }

        post.decreaseCommentCount();
        comment.softDelete();
    }

    // 댓글 수정 처리
    @Transactional
    public CommentUpdateResponse updateComment(
            Long postId,
            Long commentId,
            Long loginUserId,
            CommentUpdateRequest request
    ){
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException());

        // 삭제된 게시글 확인
        if(post.isDeleted()) {
            throw new PostNotFoundException();
        }

        Comment comment = findById(commentId);

        if (!comment.getPost().getId().equals(postId)) {
            throw new CommentNotFoundException();
        }

        if (!comment.getAuthor().getId().equals(loginUserId)) {
            throw new AccessDeniedException("댓글 작성자만 삭제할 수 있습니다.");
        }

        comment.changeContent(request.getContent());
        return new CommentUpdateResponse(
                comment.getId(),
                comment.getAuthor().getId(),
                comment.getAuthor().getNickname(),
                comment.getCreatedAt().format(FORMATTER),
                comment.getContent()
        );
    }
}
