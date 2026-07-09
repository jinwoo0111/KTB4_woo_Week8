package kr.woo.community.service;

import kr.woo.community.dto.*;
import kr.woo.community.exception.LoginFailedException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

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


    @Transactional
    public UserSignupResponse signup(UserSignupRequest request) {

        validateDuplicateEmail(request.getEmail());
        validateDuplicateNickname(request.getNickname());

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User(request.getEmail(),
                encodedPassword,
                request.getNickname(),
                request.getProfileImage()
        );
        userRepository.save(user);
        return new UserSignupResponse(
                user.getId()
        );
    }

    private void validateDuplicateEmail(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 사용중인 이메일입니다.");
        }
    }

    private void validateDuplicateNickname(String nickname) {
        if (userRepository.existsByNickname(nickname)) {
            throw new IllegalArgumentException("이미 사용중인 닉네임입니다");
        }
    }

    /*
    public UserLoginResponse login(UserLoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new LoginFailedException());

        if (user.isDeleted()) {
            throw new LoginFailedException();
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new LoginFailedException();
        }

        return new UserLoginResponse(
                user.getId()
        );
    }
    */


    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

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

    @Transactional
    public void updatePassword(Long userId, UserPasswordUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String encodedPassword = passwordEncoder.encode(request.getNewPassword());

        user.changePassword(encodedPassword);
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
