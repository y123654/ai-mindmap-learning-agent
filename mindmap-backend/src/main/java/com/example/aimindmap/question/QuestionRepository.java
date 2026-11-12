package com.example.aimindmap.question;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<QuestionEntity, Long> {
    List<QuestionEntity> findByMindmapIdOrderByIdAsc(Long mindmapId);
    List<QuestionEntity> findAllByOrderByUpdatedAtDescIdDesc();
    List<QuestionEntity> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);
}