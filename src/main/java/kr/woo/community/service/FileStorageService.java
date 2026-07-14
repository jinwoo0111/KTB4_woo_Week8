package kr.woo.community.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path uploadRoot =
            Paths.get("uploads");

    public String saveImage(
            MultipartFile file,
            String category
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "이미지 파일이 없습니다."
            );
        }

        try {
            Path categoryDirectory =
                    uploadRoot.resolve(category);
            Files.createDirectories(categoryDirectory);

            String originalFileName =
                    file.getOriginalFilename();

            String extension = "";

            if (originalFileName != null && originalFileName.contains(".")) {
                extension =
                        originalFileName.substring(
                                originalFileName.lastIndexOf(".")
                        );
            }

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
            throw new IllegalArgumentException(
                    "이미지 저장에 실패했습니다.",
                    e
            );
        }
    }

}
