package com.mio.ai.memory.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface CbtDistortionDefRepository extends JpaRepository<CbtDistortionDef, String> {

    @Query("SELECT d.code FROM CbtDistortionDef d WHERE d.code IN :codes")
    Set<String> findCodesByCodeIn(@Param("codes") Collection<String> codes);
}
