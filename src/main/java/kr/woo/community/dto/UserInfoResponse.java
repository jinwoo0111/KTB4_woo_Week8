package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserInfoResponse {

    @JsonProperty("user_id")
    private Long userId;

    private String email;

    private String nickname;

    @JsonProperty("profile_image")
    private String profileImage;
}