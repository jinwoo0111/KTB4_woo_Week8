package kr.woo.community.controller;

import kr.woo.community.common.ApiResponse;
import kr.woo.community.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    @PostMapping(
            value = "/uploads/{category}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(
            @PathVariable String category,
            @RequestPart("file") MultipartFile file
    ) {
        if(
                !category.equals("profile") &&
                        !category.equals("post")
        ) {
            ApiResponse<Map<String, String>> response =
                    new ApiResponse<>(
                            "invalid_category",
                            null
                    );

            return ResponseEntity.badRequest()
                    .body(response);
        }

        String imagePath =
                fileStorageService.saveImage(
                        file,
                        category
                );

        ApiResponse<Map<String, String>> response =
                new ApiResponse<>(
                        "upload_success",
                        Map.of(
                                "path",
                                imagePath
                        )
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}