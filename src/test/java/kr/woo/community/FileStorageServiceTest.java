package kr.woo.community;

import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileStorageServiceTest {

    private final FileStorageService fileStorageService = new FileStorageService();

    @Test
    @DisplayName("빈 이미지 파일을 업로드하면 유효하지 않은 요청 예외가 발생한다")
    void saveImageFailWhenFileIsEmpty() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.png",
                "image/png",
                new byte[0]
        );

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> fileStorageService.saveImage(emptyFile, "post")
        );

        assertEquals("image_file_empty", exception.getMessage());
    }
}
