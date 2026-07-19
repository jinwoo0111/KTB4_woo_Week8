package kr.woo.community.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.woo.community.common.ApiResponse;
import kr.woo.community.dto.UserLoginResponse;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.security.user.CustomUserDetails;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.MediaType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;


public class LoginFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;
    private final JWTUtil jwtUtil;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public LoginFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;

        setRequiresAuthenticationRequestMatcher(request ->
                "POST".equals(request.getMethod())
                        && "/users/login".equals(request.getServletPath())
        );
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
    throws AuthenticationException {
        try {
            Map<String, String> loginRequest = objectMapper.readValue(
                    request.getInputStream(),
                    new TypeReference<>() {}
            );

            if (loginRequest == null) {
                throw new BadCredentialsException("login_failed");
            }

            String email = loginRequest.get("email");
            String password = loginRequest.get("password");

            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                throw new BadCredentialsException("login_failed");
            }

            UsernamePasswordAuthenticationToken authenticationRequest =
                    new UsernamePasswordAuthenticationToken(email, password);
            return authenticationManager.authenticate(authenticationRequest);
        } catch (IOException e) {
            throw new AuthenticationServiceException("로그인 요청 처리를 실패했습니다.", e);
        }
    }

    @Override
    protected void successfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            Authentication authentication
    ) throws IOException {

        CustomUserDetails customUserDetails =
                (CustomUserDetails) authentication.getPrincipal();

        Long id = customUserDetails.getId();
        String email = customUserDetails.getEmail();

        GrantedAuthority authority = authentication.getAuthorities()
                .iterator()
                .next();

        String role = authority.getAuthority();

        String token = jwtUtil.createJwt(id, email, role, 60 * 60 * 1000L);

        response.setHeader("Authorization", "Bearer " + token);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiResponse<UserLoginResponse> apiResponse = new ApiResponse<>(
                "login_success",
                new UserLoginResponse(id)
        );

        objectMapper.writeValue(response.getWriter(), apiResponse);
    }

    @Override
    protected void unsuccessfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException failed
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiResponse<Void> apiResponse = new ApiResponse<>(
                "login_failed",
                null
        );

        objectMapper.writeValue(response.getWriter(), apiResponse);
    }
}
