package kr.woo.community;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import kr.woo.community.dto.CommentCreateRequest;
import kr.woo.community.dto.CommentUpdateRequest;
import kr.woo.community.dto.PostCreateRequest;
import kr.woo.community.dto.UserPasswordUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("게시글 생성의 제목과 내용은 공백일 수 없다")
    void validatePostCreateRequest() {
        PostCreateRequest request = new PostCreateRequest();
        ReflectionTestUtils.setField(request, "title", " ");
        ReflectionTestUtils.setField(request, "content", "");

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("댓글 생성과 수정 내용은 공백일 수 없다")
    void validateCommentRequests() {
        CommentCreateRequest createRequest = new CommentCreateRequest();
        CommentUpdateRequest updateRequest = new CommentUpdateRequest();
        ReflectionTestUtils.setField(createRequest, "content", " ");
        ReflectionTestUtils.setField(updateRequest, "content", "\t");

        assertFalse(validator.validate(createRequest).isEmpty());
        assertFalse(validator.validate(updateRequest).isEmpty());
    }

    @Test
    @DisplayName("새 비밀번호는 공백일 수 없다")
    void validatePasswordUpdateRequest() {
        UserPasswordUpdateRequest request = new UserPasswordUpdateRequest(" ");

        assertFalse(validator.validate(request).isEmpty());
    }
}
