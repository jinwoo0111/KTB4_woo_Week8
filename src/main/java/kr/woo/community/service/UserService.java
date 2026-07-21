package kr.woo.community.service;

import kr.woo.community.dto.*;
import kr.woo.community.exception.ConflictException;
import kr.woo.community.exception.InvalidRequestException;
import kr.woo.community.exception.UserNotFoundException;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final int MAX_NICKNAME_LENGTH = 10;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

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

        validateNickname(request.getNickname());
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

    @Transactional
    public UserSignupResponse signup(
            String email,
            String password,
            String nickname,
            MultipartFile profileImage
    ) {
        validateNickname(nickname);
        validateDuplicateEmail(email);
        validateDuplicateNickname(nickname);

        String profileImagePath = null;

        if(profileImage != null && !profileImage.isEmpty()) {
            profileImagePath =
                    fileStorageService.saveImage(
                            profileImage,
                            "profile"
                    );
            fileStorageService.deleteImageAfterRollback(profileImagePath);
        }

        String encodedPassword =
                passwordEncoder.encode(password);

        User user = new User(
                email,
                encodedPassword,
                nickname,
                profileImagePath
        );

        userRepository.save(user);

        return new UserSignupResponse(
                user.getId()
        );
    }

    private void validateDuplicateEmail(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("email_already_exists");
        }
    }

    private void validateDuplicateNickname(String nickname) {
        if (userRepository.existsByNickname(nickname)) {
            throw new ConflictException("nickname_already_exists");
        }
    }

    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new InvalidRequestException("nickname_blank");
        }

        if (nickname.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidRequestException("nickname_whitespace_not_allowed");
        }

        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new InvalidRequestException("nickname_too_long");
        }
    }

    private void validateNicknameForUpdate(String nickname, Long userId) {
        if (nickname == null) {
            return;
        }

        validateNickname(nickname);

        if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
            throw new ConflictException("nickname_already_exists");
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
    public UserUpdateResponse updateUser(Long userId, Long loginUserId, UserUpdateRequest request) {
        if (!userId.equals(loginUserId)) {
            throw new AccessDeniedException("본인만 회원정보를 수정할 수 있습니다.");
        }

        User user = findById(userId);

        validateNicknameForUpdate(request.getNickname(), userId);

        if(request.getNickname() != null) {
            user.changeNickname(request.getNickname());
        }
        updateProfileImage(user, request);
        return createUserUpdateResponse(user);
    }

    @Transactional
    public UserUpdateResponse updateUser(
            Long userId,
            Long loginUserId,
            String nickname,
            MultipartFile profileImage,
            boolean removeProfileImage
    ) {
        if (!userId.equals(loginUserId)) {
            throw new AccessDeniedException("본인만 회원정보를 수정할 수 있습니다.");
        }

        User user = findById(userId);
        validateNicknameForUpdate(nickname, userId);

        boolean hasNewProfileImage = profileImage != null && !profileImage.isEmpty();

        if (hasNewProfileImage && removeProfileImage) {
            throw new InvalidRequestException("profile_image_update_conflict");
        }

        if (nickname != null) {
            user.changeNickname(nickname);
        }

        if (removeProfileImage) {
            removeProfileImage(user);
        } else if (hasNewProfileImage) {
            replaceProfileImage(user, profileImage);
        }

        return createUserUpdateResponse(user);
    }

    private void replaceProfileImage(User user, MultipartFile profileImage) {
        String oldImagePath = user.getProfileImage();
        String newImagePath = fileStorageService.saveImage(profileImage, "profile");

        fileStorageService.deleteImageAfterRollback(newImagePath);
        user.changeProfileImage(newImagePath);
        fileStorageService.deleteImageAfterCommit(oldImagePath);
    }

    private void removeProfileImage(User user) {
        String oldImagePath = user.getProfileImage();

        user.changeProfileImage(null);
        fileStorageService.deleteImageAfterCommit(oldImagePath);
    }

    private UserUpdateResponse createUserUpdateResponse(User user) {
        return new UserUpdateResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage()
        );
    }

    private void updateProfileImage(User user, UserUpdateRequest request) {
        String newImagePath = request.getProfileImage();
        boolean legacyRemoveRequest = newImagePath != null && newImagePath.isBlank();
        boolean removeRequested = request.isRemoveProfileImage() || legacyRemoveRequest;

        if (request.isRemoveProfileImage() && newImagePath != null && !newImagePath.isBlank()) {
            throw new InvalidRequestException("profile_image_update_conflict");
        }

        String oldImagePath = user.getProfileImage();

        if (removeRequested) {
            user.changeProfileImage(null);
            fileStorageService.deleteImageAfterCommit(oldImagePath);
            return;
        }

        if (newImagePath != null && !newImagePath.equals(oldImagePath)) {
            user.changeProfileImage(newImagePath);
            fileStorageService.deleteImageAfterCommit(oldImagePath);
        }
    }

    @Transactional
    public void updatePassword(Long userId, Long loginUserId, UserPasswordUpdateRequest request) {
        if (!userId.equals(loginUserId)) {
            throw new AccessDeniedException("본인만 회원정보를 수정할 수 있습니다.");
        }

        User user = findById(userId);

        String encodedPassword = passwordEncoder.encode(request.getNewPassword());

        user.changePassword(encodedPassword);
    }

    // 회원탈퇴 처리
    @Transactional
    public void deleteUser(Long userId, Long loginUserId) {
        if (!userId.equals(loginUserId)) {
            throw new AccessDeniedException("본인만 회원정보를 수정할 수 있습니다.");
        }

        User user = findById(userId);
        user.softDelete();
    }

    public UserInfoResponse getCurrentUser(Long loginUserId) {
        return createUserInfoResponse(findById(loginUserId));
    }

    public UserInfoResponse getUser(Long userId, Long loginUserId) {
        if (!userId.equals(loginUserId)) {
            throw new AccessDeniedException("본인만 회원정보를 조회할 수 있습니다.");
        }

        return createUserInfoResponse(findById(userId));
    }

    private UserInfoResponse createUserInfoResponse(User user) {
        return new UserInfoResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage()
        );
    }

}
