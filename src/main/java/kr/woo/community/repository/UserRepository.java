package kr.woo.community.repository;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);

    boolean existsByNickname(String nickname);
    Optional<User> findByNickname(String nickname);
}
