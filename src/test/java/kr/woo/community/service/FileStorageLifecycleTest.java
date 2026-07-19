package kr.woo.community.service;

import kr.woo.community.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageLifecycleTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("검증된 이미지는 서버가 정한 확장자로 저장하고 삭제할 수 있다")
    void saveAndDeleteImage() {
        FileStorageService fileStorageService = new FileStorageService(tempDirectory);
        byte[] pngHeader = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "fake-name.exe",
                "image/png",
                pngHeader
        );

        String imagePath = fileStorageService.saveImage(image, "post");
        Path storedFile = tempDirectory.resolve(imagePath.substring("/uploads/".length()));

        assertTrue(imagePath.startsWith("/uploads/post/"));
        assertTrue(imagePath.endsWith(".png"));
        assertTrue(Files.exists(storedFile));

        fileStorageService.deleteImageAfterCommit(imagePath);

        assertFalse(Files.exists(storedFile));
    }

    @Test
    @DisplayName("MIME 타입과 실제 파일 시그니처가 다르면 업로드를 거부한다")
    void rejectInvalidImageSignature() {
        FileStorageService fileStorageService = new FileStorageService(tempDirectory);
        MockMultipartFile disguisedFile = new MockMultipartFile(
                "file",
                "fake.png",
                "image/png",
                "not-an-image".getBytes()
        );

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> fileStorageService.saveImage(disguisedFile, "post")
        );

        assertEquals("invalid_image_file", exception.getMessage());
    }

    @Test
    @DisplayName("허용하지 않은 MIME 타입의 업로드를 거부한다")
    void rejectUnsupportedImageType() {
        FileStorageService fileStorageService = new FileStorageService(tempDirectory);
        MockMultipartFile textFile = new MockMultipartFile(
                "file",
                "file.txt",
                "text/plain",
                "plain text".getBytes()
        );

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> fileStorageService.saveImage(textFile, "post")
        );

        assertEquals("image_type_not_allowed", exception.getMessage());
    }

    @Test
    @DisplayName("10MB를 초과한 이미지 업로드를 거부한다")
    void rejectOversizedImage() {
        FileStorageService fileStorageService = new FileStorageService(tempDirectory);
        MultipartFile oversizedFile = mock(MultipartFile.class);
        when(oversizedFile.isEmpty()).thenReturn(false);
        when(oversizedFile.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> fileStorageService.saveImage(oversizedFile, "post")
        );

        assertEquals("image_file_too_large", exception.getMessage());
    }

    @Test
    @DisplayName("기존 이미지는 트랜잭션 커밋 후에 삭제한다")
    void deleteImageOnlyAfterCommit() {
        FileStorageService fileStorageService = new FileStorageService(tempDirectory);
        byte[] pngHeader = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "image.png",
                "image/png",
                pngHeader
        );
        String imagePath = fileStorageService.saveImage(image, "profile");
        Path storedFile = tempDirectory.resolve(imagePath.substring("/uploads/".length()));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            fileStorageService.deleteImageAfterCommit(imagePath);
            assertTrue(Files.exists(storedFile));

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertFalse(Files.exists(storedFile));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
