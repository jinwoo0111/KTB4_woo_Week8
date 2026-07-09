package kr.woo.community;

import kr.woo.community.dto.*;
import kr.woo.community.entity.User;
import kr.woo.community.exception.UserNotFoundException;
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

    @Test
    @DisplayName("프로필 이미지만 수정하면 닉네임은 유지되고 프로필 이미지만 변경된다")
    void updateProfileImageSuccess() {
        Long userId = 1L;

        User user = new User(
                "test@test.com",
                "Test1234!",
                "old닉네임",
                "oldProfileImage"
        );

        UserUpdateRequest request = new UserUpdateRequest(
                null,
                "newProfileImage"
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateUser(userId, request);
        assertEquals("old닉네임", user.getNickname());
        assertEquals("newProfileImage", user.getProfileImage());
    }

    @Test
    @DisplayName("닉네임만 수정하면, profileImage는 유지되고 닉네임만 수정된다")
    void updateNicknameSuccess() {
        Long userId = 1L;

        User user = new User(
                "test@test.com",
                "Test1234!",
                "old닉네임",
                "oldProfileImage"
        );

        UserUpdateRequest request = new UserUpdateRequest(
                "new닉네임",
                null
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        userService.updateUser(userId, request);
        assertEquals("new닉네임", user.getNickname());
        assertEquals("oldProfileImage", user.getProfileImage());
    }

    @Test
    @DisplayName("회원정보 수정 시, 유저가 존재하지 않으면 예외가 발생한다")
    void updateUserFail() {
         Long userId = 1L;
         UserUpdateRequest request = new UserUpdateRequest(
                 null,
                 null
         );
         when(userRepository.findById(userId)).thenReturn(Optional.empty());
         assertThrows(UserNotFoundException.class, ()-> {
             userService.updateUser(userId, request);
         });

    }

    @Test
    @DisplayName("비밀번호 수정 성공")
    void updatePasswordSuccess() {
        Long userId = 1L;
        User user = new User(
                "test@test.com",
                "oldPassword1234!",
                "test닉네임",
                "testProfileImage"
        );
        UserPasswordUpdateRequest request = new UserPasswordUpdateRequest(
                "newPassword1234!"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String newPassword
    }
}


