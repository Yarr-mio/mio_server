package com.mio.ai.memory.ontology;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * behavior_template 조회. 시드된 소량 온톨로지 테이블이므로 후보 선별·스코어링은
 * {@code findAll()} 결과를 서비스 레이어(Java)에서 처리한다.
 * (고정 정렬 네이티브 쿼리는 항상 동일 Todo만 노출시키던 원인이라 제거 — 이슈 #228)
 */
public interface BehaviorTemplateRepository extends JpaRepository<BehaviorTemplate, String> {
}
