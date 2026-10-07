package com.mio.ai.qa.correction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 한 실행의 신원 (이슈 #552). 서로 다른 세트 버전·기준표·모델·반복 횟수로 잰 결과를 실수로 비교하지
 * 않게, 결과와 항상 함께 다닌다. 세트 해시는 평가용 세트가 확정 뒤 바뀌지 않았음을 확인하는 데 쓴다.
 */
record CorrectionRunIdentity(String runId, Instant startedAt, String setVersion, String setSha256,
                             int repeats, List<String> modes, List<String> arms,
                             String generationModel, String judgeModel, String rubricVersion) {

    static CorrectionRunIdentity stamp(String setVersion, String setSha256, int repeats,
                                       List<String> modes, List<String> arms,
                                       String generationModel, String judgeModel) {
        return new CorrectionRunIdentity(UUID.randomUUID().toString(), Instant.now(), setVersion, setSha256,
                repeats, List.copyOf(modes), List.copyOf(arms), generationModel, judgeModel,
                CorrectionRubric.VERSION);
    }
}
