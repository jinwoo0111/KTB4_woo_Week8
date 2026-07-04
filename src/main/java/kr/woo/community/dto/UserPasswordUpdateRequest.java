package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 비밀번호 수정 요청 DTO
@Getter
@NoArgsConstructor
public class UserPasswordUpdateRequest {

    @JsonProperty("new_password")
    @NotBlank
    private String newPassword;
}