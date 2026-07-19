package kr.woo.community;

import kr.woo.community.dto.*;
import kr.woo.community.entity.User;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.UserService;
import kr.woo.community.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import static org.mockito.ArgumentMatchers.anyLong;
import org.mockito.ArgumentCaptor;

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

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("회원가입 시 이메일이 중복되면 예외가 발생해야한다")
    void signupFailWhenEmailDuplicated() {

        UserSignupRequest request = new UserSignupRequest("test@test.com", "Test1234!", "Test계정", "profile_image");
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        ConflictException exception = assertThrows(ConflictException.class, () -> {
            userService.signup(request);
        });

        assertEquals("email_already_exists", exception.getMessage());
        verify(userRepository, times(1)).existsByEmail(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 시 닉네임이 중복되면 예외가 발생해야한다")
    void signupFailWhenNicknameDuplicated() {

        UserSignupRequest request = new UserSignupRequest("test@test.com", "Test1234!", "Test계정입니다", "profile_image");

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByNickname(anyString())).thenReturn(true);

        ConflictException exception = assertThrows(ConflictException.class, () -> {
            userService.signup(request);
        });

        assertEquals("nickname_already_exists", exception.getMessage());
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

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        verify(userRepository, times(1)).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertEquals("encodedPassword", savedUser.getPassword());
    }

    @Test
    @DisplayName("회원 정보 수정 성공")
    void updateUserSuccess() {

        Long userId = 1L;
        Long loginUserId = 1L;

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

        userService.updateUser(userId, loginUserId, request);

        assertEquals("new닉네임", user.getNickname());
        assertEquals("newProfileImage", user.getProfileImage());
    }

    @Test
    @DisplayName("회원 정보 수정 실패 - 본인이 아닌 경우 AccessDeniedException")
    void updateUserFailWhenNotOwner() {
        Long userId = 1L;
        Long loginUserId = 2L;

        UserUpdateRequest request = new UserUpdateRequest(
                "new닉네임",
                "newProfileImage"
        );

        assertThrows(AccessDeniedException.class, ()->{
            userService.updateUser(userId, loginUserId, request);
        });
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("회원 정보 수정 실패 - 다른 사용자가 사용 중인 닉네임")
    void updateUserFailWhenNicknameDuplicated() {
        Long userId = 1L;
        Long loginUserId = 1L;

        User user = new User(
                "test@test.com",
                "Test1234!",
                "old닉네임",
                "oldProfileImage"
        );

        UserUpdateRequest request = new UserUpdateRequest(
                "duplicated닉네임",
                null
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("duplicated닉네임", userId))
                .thenReturn(true);

        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> userService.updateUser(userId, loginUserId, request)
        );

        assertEquals("nickname_already_exists", exception.getMessage());
        assertEquals("old닉네임", user.getNickname());
    }

    @Test
    @DisplayName("회원 정보 수정 실패 - 닉네임이 공백인 경우")
    void updateUserFailWhenNicknameIsBlank() {
        Long userId = 1L;
        Long loginUserId = 1L;
        User user = new User(
                "test@test.com",
                "Test1234!",
                "old닉네임",
                "oldProfileImage"
        );
        UserUpdateRequest request = new UserUpdateRequest("   ", null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> userService.updateUser(userId, loginUserId, request)
        );

        assertEquals("nickname_blank", exception.getMessage());
        assertEquals("old닉네임", user.getNickname());
        verify(userRepository, never()).existsByNicknameAndIdNot(anyString(), anyLong());
    }

    @Test
    @DisplayName("프로필 이미지만 수정하면 닉네임은 유지되고 프로필 이미지만 변경된다")
    void updateProfileImageSuccess() {
        Long userId = 1L;
        Long loginUserId = 1L;

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

        userService.updateUser(userId, loginUserId, request);
        assertEquals("old닉네임", user.getNickname());
        assertEquals("newProfileImage", user.getProfileImage());
        verify(fileStorageService).deleteImageAfterCommit("oldProfileImage");
    }

    @Test
    @DisplayName("닉네임만 수정하면, profileImage는 유지되고 닉네임만 수정된다")
    void updateNicknameSuccess() {
        Long userId = 1L;
        Long loginUserId = 1L;

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
        userService.updateUser(userId, loginUserId, request);
        assertEquals("new닉네임", user.getNickname());
        assertEquals("oldProfileImage", user.getProfileImage());
        verify(fileStorageService, never()).deleteImageAfterCommit(anyString());
    }

    @Test
    @DisplayName("회원정보 수정 시, 유저가 존재하지 않으면 예외가 발생한다")
    void updateUserFail() {
         Long userId = 1L;
        Long loginUserId = 1L;
         UserUpdateRequest request = new UserUpdateRequest(
                 null,
                 null
         );
         when(userRepository.findById(userId)).thenReturn(Optional.empty());
         assertThrows(UserNotFoundException.class, ()-> {
             userService.updateUser(userId, loginUserId,request);
         });

    }

    @Test
    @DisplayName("비밀번호 수정 성공")
    void updatePasswordSuccess() {
        Long userId = 1L;
        Long loginUserId = 1L;
        User user = new User(
                "test@test.com",
                "oldPassword1234!",
                "test닉네임",
                "testProfileImage"
        );
        String rawPassword = "newPassword1234!";
        UserPasswordUpdateRequest request = new UserPasswordUpdateRequest(
                rawPassword
        );

        String encodedPassword = "encodedPassword";

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        userService.updatePassword(userId, loginUserId, request);
        assertEquals(encodedPassword, user.getPassword());
    }

    @Test
    @DisplayName("비밀번호 수정 시, 유저가 존재하지 않으면 예외가 발생한다")
    void updatePasswordFail() {
        Long userId = 1L;
        Long loginUserId = 1L;
        UserPasswordUpdateRequest request = new UserPasswordUpdateRequest(
                "newPw1234!"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, ()-> {
            userService.updatePassword(userId, loginUserId,request);
        });
    }

    @Test
    @DisplayName("현재 로그인한 회원정보 조회 성공")
    void getCurrentUserSuccess() {
        Long userId = 1L;
        User user = new User(
                "test@test.com",
                "password",
                "nickname",
                "profileImage"
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserInfoResponse response = userService.getCurrentUser(userId);

        assertEquals("test@test.com", response.getEmail());
        assertEquals("nickname", response.getNickname());
        assertEquals("profileImage", response.getProfileImage());
    }

    @Test
    @DisplayName("다른 사용자의 회원정보 조회는 거부한다")
    void getUserFailWhenNotOwner() {
        Long userId = 1L;
        Long loginUserId = 2L;

        assertThrows(
                AccessDeniedException.class,
                () -> userService.getUser(userId, loginUserId)
        );

        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("프로필 이미지를 명시적으로 제거하면 null로 변경하고 기존 파일을 삭제한다")
    void updateUserRemovesProfileImage() {
        Long userId = 1L;
        User user = new User(
                "test@test.com",
                "password",
                "nickname",
                "/uploads/profile/old.png"
        );
        UserUpdateRequest request = new UserUpdateRequest(null, null, true);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserUpdateResponse response = userService.updateUser(userId, userId, request);

        assertNull(user.getProfileImage());
        assertNull(response.getProfileImage());
        verify(fileStorageService).deleteImageAfterCommit("/uploads/profile/old.png");
    }

    @Test
    @DisplayName("프로필 이미지 교체와 제거를 동시에 요청하면 400 예외가 발생한다")
    void updateUserFailsWhenProfileImageRequestConflicts() {
        Long userId = 1L;
        User user = new User(
                "test@test.com",
                "password",
                "nickname",
                "/uploads/profile/old.png"
        );
        UserUpdateRequest request = new UserUpdateRequest(
                null,
                "/uploads/profile/new.png",
                true
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> userService.updateUser(userId, userId, request)
        );

        assertEquals("profile_image_update_conflict", exception.getMessage());
        assertEquals("/uploads/profile/old.png", user.getProfileImage());
        verify(fileStorageService, never()).deleteImageAfterCommit(anyString());
    }

    @Test
    @DisplayName("회원탈퇴 성공 시 유저가 삭제 상태로 변경된다")
    void deleteUserSuccess() {
        Long userId = 1L;
        Long loginUserId = 1L;

        User user = new User(
                "test@test.com",
                "Test1234!",
                "test닉네임",
                "testProfileImage"
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        userService.deleteUser(userId, loginUserId);

        assertTrue(user.isDeleted());
    }

    @Test
    @DisplayName("회원탈퇴 시 유저가 존재하지 않으면 예외가 발생한다")
    void deleteUserFail() {
        Long userId = 1L;
        Long loginUserId = 1L;
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> {
            userService.deleteUser(userId, loginUserId);
        });
    }
}
