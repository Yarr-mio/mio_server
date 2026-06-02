package com.mio.ai.memory.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BehaviorTemplateRepository extends JpaRepository<BehaviorTemplate, String> {

    @Query(value = """
            SELECT * FROM behavior_template
            WHERE :distortionCode = ANY(fits_distortions)
               OR :emotionCode = ANY(fits_emotions)
            ORDER BY difficulty ASC
            """, nativeQuery = true)
    List<BehaviorTemplate> findByDistortionOrEmotion(String distortionCode, String emotionCode);
}
