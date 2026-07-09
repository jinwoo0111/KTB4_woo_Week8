package kr.woo.community;

import kr.woo.community.dto.*;
import kr.woo.community.entity.User;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class UserTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("회원가입 시 이메일이 중복되면 예외가 발생해야한다")
    void validateDulplicateEmailSuccess() {

        UserSignupRequest request = new UserSignupRequest("test@test.com", "Test1234!", "Test계정", "profile_image");
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> {
            userService.signup(request);
        });

        verify(userRepository, times(1)).existsByEmail(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 시 닉네임이 중복되면 예외가 발생해야한다")
    void validateDulplicateNicknameSuccess() {

        UserSignupRequest request = new UserSignupRequest("test@test.com", "Test1234!", "Test계정입니다", "profile_image");

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByNickname(anyString())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> {
            userService.signup(request);
        });

        verify(userRepository, times(1)).existsByNickname(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입에 성공하여 유저 저장을 요청한다")
    void signupSuccess() {
        UserSignupRequest request = new UserSignupRequest("test@test.com", "Test1234!", "Test계정입니다", "profile_image");

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByNickname(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");

        userService.signup(request);

        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("회원 정보 수정 성공")
    void updateUserSuccess() {

        Long userId = 1L;

        User user = new User(
                "test@test.com",
                "Test1234!",
                "old닉네임",
                "oldProfileImage"
        );

        UserUpdateRequest request = new UserUpdateRequest(
                "new닉네임",
                "newProfileImage"
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateUser(userId, request);

        assertEquals("new닉네임", user.getNickname());
        assertEquals("newProfileImage", user.getProfileImage());
    }

}

