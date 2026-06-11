package com.mio.ai.memory.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface EmotionDefRepository extends JpaRepository<EmotionDef, String> {

    @Query("SELECT e.code FROM EmotionDef e WHERE e.code IN :codes")
    Set<String> findCodesByCodeIn(@Param("codes") Collection<String> codes);
}
