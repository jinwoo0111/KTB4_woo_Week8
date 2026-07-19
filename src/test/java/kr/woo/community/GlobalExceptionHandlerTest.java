package kr.woo.community;

import kr.woo.community.common.ApiResponse;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.GlobalExceptionHandler;
import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.exception.PostLikeNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    @DisplayName("충돌 예외는 409 상태 코드와 예외 메시지를 반환한다")
    void handleConflictException() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleConflictException(
                new ConflictException("email_already_exists")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("email_already_exists", response.getBody().getMessage());
    }

    @Test
    @DisplayName("존재하지 않는 좋아요 예외는 404 상태 코드와 예외 메시지를 반환한다")
    void handlePostLikeNotFoundException() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handlePostLikeNotFoundException(
                new PostLikeNotFoundException()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("post_like_not_found", response.getBody().getMessage());
    }

    @Test
    @DisplayName("유효하지 않은 요청 예외는 400 상태 코드와 예외 메시지를 반환한다")
    void handleInvalidRequestException() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleInvalidRequestException(
                new InvalidRequestException("title_blank")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("title_blank", response.getBody().getMessage());
    }

    @Test
    @DisplayName("최대 업로드 크기를 초과하면 400 응답을 반환한다")
    void handleMaxUploadSizeExceededException() {
        ResponseEntity<ApiResponse<Void>> response =
                exceptionHandler.handleMaxUploadSizeExceededException(
                        new MaxUploadSizeExceededException(10L * 1024 * 1024)
                );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("image_file_too_large", response.getBody().getMessage());
    }

    @Test
    @DisplayName("필수 multipart 파일이 누락되면 공통 형식의 400 응답을 반환한다")
    void handleMissingRequestPartException() {
        ResponseEntity<ApiResponse<Map<String, String>>> response =
                exceptionHandler.handleMissingRequestPartException(
                        new MissingServletRequestPartException("file")
                );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("invalid_request", response.getBody().getMessage());
        assertEquals("required", response.getBody().getData().get("file"));
    }
}
