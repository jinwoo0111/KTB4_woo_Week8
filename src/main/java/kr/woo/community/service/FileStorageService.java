package kr.woo.community.service;

import kr.woo.community.exception.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp"
    );

    private final Path uploadRoot;

    public FileStorageService() {
        this(Paths.get("uploads"));
    }

    FileStorageService(Path uploadRoot) {
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
    }

    public String saveImage(
        MultipartFile file,
            String category
    ) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("image_file_empty");
        }

        if (!category.equals("profile") && !category.equals("post")) {
            throw new InvalidRequestException("invalid_category");
        }

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new InvalidRequestException("image_file_too_large");
        }

        String contentType = file.getContentType();
        String extension = ALLOWED_IMAGE_EXTENSIONS.get(contentType);

        if (extension == null) {
            throw new InvalidRequestException("image_type_not_allowed");
        }

        try {
            validateImageSignature(file, contentType);

            Path categoryDirectory =
                    uploadRoot.resolve(category);
            Files.createDirectories(categoryDirectory);

            String storedFileName =
                    UUID.randomUUID() + extension;

            Path targetPath =
                    categoryDirectory.resolve(
                            storedFileName
                    );
            Files.copy(
                    file.getInputStream(),
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
            );

            return "/uploads/"
                    + category
                    + "/"
                    + storedFileName;
        } catch(IOException e) {
            throw new IllegalStateException("image_save_failed", e);
        }
    }

    public void deleteImageAfterCommit(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return;
        }

        Runnable deleteAction = () -> deleteManagedImage(imagePath);

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteAction.run();
                        }
                    }
            );
            return;
        }

        deleteAction.run();
    }

    private void deleteManagedImage(String imagePath) {
        if (!imagePath.startsWith("/uploads/")) {
            log.warn("관리 대상이 아닌 이미지 삭제 요청을 무시합니다: {}", imagePath);
            return;
        }

        Path relativePath = Paths.get(imagePath.substring("/uploads/".length())).normalize();
        Path targetPath = uploadRoot.resolve(relativePath).normalize();

        if (!targetPath.startsWith(uploadRoot)) {
            log.warn("업로드 경로를 벗어난 이미지 삭제 요청을 무시합니다: {}", imagePath);
            return;
        }

        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException e) {
            log.error("기존 이미지 파일 삭제에 실패했습니다: {}", imagePath, e);
        }
    }

    private void validateImageSignature(MultipartFile file, String contentType) throws IOException {
        byte[] header = file.getInputStream().readNBytes(12);

        boolean valid = switch (contentType) {
            case "image/jpeg" -> header.length >= 3
                    && unsigned(header[0]) == 0xFF
                    && unsigned(header[1]) == 0xD8
                    && unsigned(header[2]) == 0xFF;
            case "image/png" -> header.length >= 8
                    && unsigned(header[0]) == 0x89
                    && header[1] == 'P'
                    && header[2] == 'N'
                    && header[3] == 'G'
                    && unsigned(header[4]) == 0x0D
                    && unsigned(header[5]) == 0x0A
                    && unsigned(header[6]) == 0x1A
                    && unsigned(header[7]) == 0x0A;
            case "image/gif" -> header.length >= 6
                    && header[0] == 'G'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == '8'
                    && (header[4] == '7' || header[4] == '9')
                    && header[5] == 'a';
            case "image/webp" -> header.length >= 12
                    && header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P';
            default -> false;
        };

        if (!valid) {
            throw new InvalidRequestException("invalid_image_file");
        }
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
}
