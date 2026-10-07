package com.mio.ai.qa.correction;

import com.mio.ai.plan.ResponseContractValidator;

/**
 * 채점 모델의 판단이 필요 없는 객관 지표 (이슈 #551). 프로덕션 {@code ResponseContractValidator}
 * 의 세는 규칙을 그대로 써서, 하네스가 프로덕션과 다른 자로 질문·문장을 세지 않게 한다.
 *
 * @param questions 물음표 개수. 정정 턴의 목표는 0 이다 (꼬리질문은 ⑤로 이월)
 * @param sentences 문장 수. 페르소나의 "2-4문장" 규칙과 비교한다
 */
record CorrectionResponseShape(int questions, int sentences) {

    private static final ResponseContractValidator VALIDATOR = new ResponseContractValidator();

    static CorrectionResponseShape of(String response) {
        return new CorrectionResponseShape(
                VALIDATOR.countQuestions(response), VALIDATOR.countSentences(response));
    }

    boolean withinPersonaLength() {
        return sentences >= 2 && sentences <= 4;
    }
}
