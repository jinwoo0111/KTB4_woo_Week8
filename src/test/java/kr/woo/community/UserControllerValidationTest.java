package kr.woo.community;

import kr.woo.community.controller.UserController;
import kr.woo.community.dto.UserInfoResponse;
import kr.woo.community.entity.Role;
import kr.woo.community.security.config.SecurityConfig;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.security.user.CustomUserDetails;
import kr.woo.community.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JWTUtil jwtUtil;

    @Test
    @DisplayName("회원가입 이메일 형식이 올바르지 않으면 400 응답을 반환한다")
    void signupFailWhenEmailIsInvalid() throws Exception {
        mockMvc.perform(multipart("/users/signup")
                        .param("email", "invalid-email")
                        .param("password", "password")
                        .param("nickname", "nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_request"))
                .andExpect(jsonPath("$.data.email").exists());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("회원가입 필수 문자열이 공백이면 400 응답을 반환한다")
    void signupFailWhenRequiredValueIsBlank() throws Exception {
        mockMvc.perform(multipart("/users/signup")
                        .param("email", "test@test.com")
                        .param("password", "   ")
                        .param("nickname", "nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_request"))
                .andExpect(jsonPath("$.data.password").exists());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("회원가입 필수 파라미터가 누락되면 공통 형식의 400 응답을 반환한다")
    void signupFailWhenRequiredParameterIsMissing() throws Exception {
        mockMvc.perform(multipart("/users/signup")
                        .param("email", "test@test.com")
                        .param("nickname", "nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_request"))
                .andExpect(jsonPath("$.data.password").value("required"));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("로그인 사용자는 자신의 회원정보를 /users/me로 조회할 수 있다")
    void getCurrentUserSuccess() throws Exception {
        Long userId = 1L;
        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "test@test.com",
                Role.USER
        );
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

        when(userService.getCurrentUser(userId)).thenReturn(
                new UserInfoResponse(userId, "test@test.com", "nickname", null)
        );

        mockMvc.perform(get("/users/me").with(authentication(authenticationToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("user_get_success"))
                .andExpect(jsonPath("$.data.user_id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@test.com"))
                .andExpect(jsonPath("$.data.nickname").value("nickname"))
                .andExpect(jsonPath("$.data.profile_image").isEmpty());
    }

    @Test
    @DisplayName("비로그인 사용자의 /users/me 요청은 401 응답을 반환한다")
    void getCurrentUserFailWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
