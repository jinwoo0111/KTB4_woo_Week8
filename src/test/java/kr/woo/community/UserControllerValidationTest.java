package kr.woo.community;

import kr.woo.community.controller.UserController;
import kr.woo.community.security.config.SecurityConfig;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
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
}
