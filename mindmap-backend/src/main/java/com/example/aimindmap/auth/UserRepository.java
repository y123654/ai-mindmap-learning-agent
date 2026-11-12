package com.example.aimindmap.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsernameIgnoreCase(String username);

    /**
     * 第一个注册用户接管历史数据：把所有还没有归属的业务数据分配给他。
     */
    @Modifying
    @Query(value = "UPDATE documents SET user_id = :userId WHERE user_id IS NULL", nativeQuery = true)
    int claimDocuments(Long userId);

    @Modifying
    @Query(value = "UPDATE mindmaps SET user_id = :userId WHERE user_id IS NULL", nativeQuery = true)
    int claimMindmaps(Long userId);

    @Modifying
    @Query(value = "UPDATE questions SET user_id = :userId WHERE user_id IS NULL", nativeQuery = true)
    int claimQuestions(Long userId);
}