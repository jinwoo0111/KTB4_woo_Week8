package kr.woo.community.security.filter;

import jakarta.servlet.FilterChain;
import kr.woo.community.entity.Role;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.security.user.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginFilterTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final JWTUtil jwtUtil = mock(JWTUtil.class);
    private final TestableLoginFilter loginFilter = new TestableLoginFilter(
            authenticationManager,
            jwtUtil
    );

    @Test
    @DisplayName("로그인 성공 시 토큰 헤더와 사용자 ID가 포함된 JSON을 반환한다")
    void successfulAuthenticationReturnsTokenAndUserId() throws IOException {
        Long userId = 1L;
        String email = "test@test.com";
        CustomUserDetails userDetails = new CustomUserDetails(userId, email, Role.USER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtil.createJwt(userId, email, "USER", 60 * 60 * 1000L))
                .thenReturn("access-token");

        loginFilter.invokeSuccessfulAuthentication(
                new MockHttpServletRequest(),
                response,
                mock(FilterChain.class),
                authentication
        );

        assertEquals(200, response.getStatus());
        assertEquals("Bearer access-token", response.getHeader("Authorization"));
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())
        ));
        assertTrue(response.getContentAsString().contains("\"message\":\"login_success\""));
        assertTrue(response.getContentAsString().contains("\"user_id\":1"));
        verify(jwtUtil).createJwt(userId, email, "USER", 60 * 60 * 1000L);
    }

    @Test
    @DisplayName("로그인 실패 시 공통 형식의 401 JSON을 반환한다")
    void unsuccessfulAuthenticationReturnsJson() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginFilter.invokeUnsuccessfulAuthentication(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("bad credentials")
        );

        assertEquals(401, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())
        ));
        assertTrue(response.getContentAsString().contains("\"message\":\"login_failed\""));
        assertTrue(response.getContentAsString().contains("\"data\":null"));
    }

    @Test
    @DisplayName("로그인 필수 값이 누락되면 인증 관리자 호출 없이 로그인 실패로 처리한다")
    void attemptAuthenticationFailsWhenRequiredValueIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(
                "{\"email\":\"test@test.com\"}".getBytes(StandardCharsets.UTF_8)
        );

        BadCredentialsException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BadCredentialsException.class,
                () -> loginFilter.attemptAuthentication(request, new MockHttpServletResponse())
        );

        assertEquals("login_failed", exception.getMessage());
        verify(authenticationManager, org.mockito.Mockito.never())
                .authenticate(org.mockito.ArgumentMatchers.any());
    }

    private static class TestableLoginFilter extends LoginFilter {

        TestableLoginFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil) {
            super(authenticationManager, jwtUtil);
        }

        void invokeSuccessfulAuthentication(
                MockHttpServletRequest request,
                MockHttpServletResponse response,
                FilterChain chain,
                Authentication authentication
        ) throws IOException {
            successfulAuthentication(request, response, chain, authentication);
        }

        void invokeUnsuccessfulAuthentication(
                MockHttpServletRequest request,
                MockHttpServletResponse response,
                BadCredentialsException exception
        ) throws IOException {
            unsuccessfulAuthentication(request, response, exception);
        }
    }
}
