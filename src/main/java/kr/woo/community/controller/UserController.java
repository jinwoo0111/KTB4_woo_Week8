package kr.woo.community.controller;

import jakarta.validation.Valid;
import kr.woo.community.common.ApiResponse;
import kr.woo.community.dto.*;
import kr.woo.community.service.UserService;
import kr.woo.community.security.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    // POST /users/signup - 회원가입
    @PostMapping("/users/signup")
    public ResponseEntity<ApiResponse<UserSignupResponse>> signup(
            @Valid @RequestBody UserSignupRequest request
    ) {
        UserSignupResponse userSignupResponse = userService.signup(request);
        ApiResponse<UserSignupResponse> response = new ApiResponse<>(
                "register_success",
                userSignupResponse
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }


    /*
    //ServletFilter 단계에서 login 담당해서 제외


    // POST /users/login - 로그인
    @PostMapping("/users/login")
    public ResponseEntity<ApiResponse<UserLoginResponse>> login(
            @Valid @RequestBody UserLoginRequest request
            ) {
        UserLoginResponse userLoginResponse = userService.login(request);
        ApiResponse<UserLoginResponse> response = new ApiResponse<>(
                "login_success",
                userLoginResponse
        );
        return ResponseEntity.ok(response);
    }
    */

    // PATCH /users/{userId} - 회원정보 수정
    @PatchMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserUpdateResponse>> updateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        UserUpdateResponse updateResponse = userService.updateUser(userId, loginUser.getId(), request);

        ApiResponse<UserUpdateResponse> response = new ApiResponse<>(
                "user_update_success",
                updateResponse
        );

        return ResponseEntity.ok(response);
    }

    // PATCH /users/{userId}/password - 비밀번호 수정
    @PatchMapping("/users/{userId}/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @Valid @RequestBody UserPasswordUpdateRequest request
    ) {
        userService.updatePassword(userId, loginUser.getId(), request);

        ApiResponse<Void> response = new ApiResponse<>(
                "password_update_success",
                null
        );

        return ResponseEntity.ok(response);
    }

    // DELETE /users/{userId} - 회원탈퇴
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails loginUser
    ) {
        userService.deleteUser(userId, loginUser.getId());

        ApiResponse<Void> response = new ApiResponse<>(
                "user_delete_success",
                null
        );

        return ResponseEntity.ok(response);
    }

    // GET /users/{userId} - 회원정보 조회
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserInfoResponse>> getUser(
            @PathVariable Long userId
    ) {
        UserInfoResponse userInfoResponse = userService.getUser(userId);

        ApiResponse<UserInfoResponse> response = new ApiResponse<>(
                "user_get_success",
                userInfoResponse
        );

        return ResponseEntity.ok(response);
    }
}



