package kr.woo.community.service;

import kr.woo.community.dto.*;
import kr.woo.community.exception.LoginFailedException;
import kr.woo.community.exception.PasswordMismatchException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;


    public User findById(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException());

        if(user.isDeleted()) {
            throw new UserNotFoundException();
        }
        return user;
    }

    public User getReferenceById(Long id) {
        return userRepository.getReferenceById(id);
    }

    // 회원가입 처리
    @Transactional
    public UserSignupResponse signup(UserSignupRequest request) {
        User user = new User(request.getEmail(),
                request.getPassword(),
                request.getNickname(),
                request.getProfileImage()
        );
        userRepository.save(user);
        return new UserSignupResponse(
                user.getId()
        );
    }

    // 로그인 처리
    public UserLoginResponse login(UserLoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new LoginFailedException());

        if (user.isDeleted()) {
            throw new LoginFailedException();
        }

        if (!user.getPassword().equals(request.getPassword())) {
            throw new LoginFailedException();
        }

        return new UserLoginResponse(
                user.getId()
        );
    }

    // 회원정보 수정 처리
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        User user = findById(userId);
        if(request.getNickname()!=null) {
            user.changeNickname(request.getNickname());
        }
        if(request.getProfileImage()!=null) {
            user.changeProfileImage(request.getProfileImage());
        }
        return new UserUpdateResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage()
        );
    }

    // 비밀번호 수정 처리
    @Transactional
    public void updatePassword(Long userId, UserPasswordUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.changePassword(request.getNewPassword());
    }

    // 회원탈퇴 처리
    @Transactional
    public void deleteUser(Long userId) {
        User user = findById(userId);
        user.softDelete();
    }

    public UserInfoResponse getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        return new UserInfoResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage()
        );
    }

}
