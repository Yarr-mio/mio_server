/**
 * GitHub Issues → Notion 이슈 & 버그 트래킹 DB 동기화 스크립트
 *
 * 필요한 GitHub Secrets:
 *   NOTION_TOKEN            - Notion Integration Token
 *   NOTION_ISSUE_DATABASE_ID - 이슈 & 버그 트래킹 DB ID (f7d4482bc8fa42689261e5e5346637a0)
 */

const { Client } = require('@notionhq/client');

const notion = new Client({ auth: process.env.NOTION_TOKEN });
const DATABASE_ID = process.env.NOTION_DATABASE_ID;

// ─── 매핑 테이블 ──────────────────────────────────────────────────────────────

/** GitHub 이벤트 액션 → Notion 상태 */
const ACTION_TO_STATUS = {
  opened:    '확인됨',
  edited:    null,       // 상태 변경 없음
  closed:    '완료',
  reopened:  '진행중',
  labeled:   null,
  unlabeled: null,
  assigned:  null,
  unassigned: null,
};

/** GitHub 레이블 → Notion 유형 */
const LABEL_TO_TYPE = {
  bug:         '버그',
  enhancement: '기능 개선',
  question:    '질문',
  refactor:    '리팩토링',
  performance: '성능',
};

/** 이슈 제목 prefix → Notion 유형 (레이블 없을 때 fallback) */
const PREFIX_TO_TYPE = {
  '[BUG]':  '버그',
  '[FEAT]': '기능 개선',
  '[DOCS]': '질문',
  '[REFACTOR]': '리팩토링',
  '[PERF]': '성능',
};

/**
 * GitHub 이슈 템플릿의 'area' 드롭다운 값 → Notion 관련 도메인
 * (이슈 본문에서 파싱)
 */
const AREA_TO_DOMAIN = {
  'API':           '공통',
  '인증 / 인가':   'Auth',
  '인증/인가':     'Auth',
  'DB / 영속성':   'DB/인프라',
  'DB / 데이터 모델': 'DB/인프라',
  'DB/영속성':     'DB/인프라',
  'DB/데이터 모델': 'DB/인프라',
  '배치 / 스케줄러': '공통',
  '배치/스케줄러': '공통',
  '외부 연동':     '공통',
  '외부연동':      '공통',
  '인프라 / 배포': 'DB/인프라',
  '인프라/배포':   'DB/인프라',
  '성능 / 안정성': '공통',
  '성능/안정성':   '공통',
  '운영 / 관측성': 'DB/인프라',
  '운영/관측성':   'DB/인프라',
  '개발 생산성':   '공통',
  '개발생산성':    '공통',
};

// ─── 헬퍼 함수 ────────────────────────────────────────────────────────────────

/** 이슈 본문에서 선택된 드롭다운 값 파싱 (GitHub form 템플릿 형식) */
function parseAreaFromBody(body = '') {
  // "### 영향 범위\n\nAPI" 또는 "### 제안 영역\n\nAPI" 형태
  const match = body.match(/###\s+(?:영향\s+범위|제안\s+영역)\s*\n+(.+)/);
  if (!match) return null;
  const raw = match[1].trim();
  return AREA_TO_DOMAIN[raw] || null;
}

/** 이슈 본문에서 환경 파싱 */
function parseEnvironmentFromBody(body = '') {
  const match = body.match(/실행 방식[:\s]+(?:local|dev|staging|prod)/i);
  if (!match) return null;
  const env = match[0].match(/(local|dev|staging|prod)/i);
  return env ? env[1].toLowerCase() : null;
}

/** GitHub 레이블 배열에서 Notion 유형 결정 */
function detectType(labels, title) {
  for (const label of labels) {
    const type = LABEL_TO_TYPE[label.name.toLowerCase()];
    if (type) return type;
  }
  // 레이블 없으면 제목 prefix로 판단
  for (const [prefix, type] of Object.entries(PREFIX_TO_TYPE)) {
    if (title.startsWith(prefix)) return type;
  }
  return '질문';
}

/** 이미 Notion에 등록된 페이지 조회 */
async function findExistingPage(issueNumber) {
  const res = await notion.databases.query({
    database_id: DATABASE_ID,
    filter: {
      property: 'GitHub 이슈 번호',
      number: { equals: issueNumber },
    },
  });
  return res.results[0] || null;
}

// ─── 메인 ─────────────────────────────────────────────────────────────────────

async function main() {
  const issueNumber = parseInt(process.env.ISSUE_NUMBER, 10);
  const issueTitle  = process.env.ISSUE_TITLE  || '';
  const issueUrl    = process.env.ISSUE_URL    || '';
  const issueState  = process.env.ISSUE_STATE  || 'open';
  const issueBody   = process.env.ISSUE_BODY   || '';
  const eventAction = process.env.EVENT_ACTION || 'opened';

  const labels    = JSON.parse(process.env.ISSUE_LABELS   || '[]');
  const assignees = JSON.parse(process.env.ISSUE_ASSIGNEES || '[]');

  console.log(`\n📌 이슈 #${issueNumber} [${eventAction}] 동기화 시작`);

  // ── 속성 구성 ──────────────────────────────────────────────────────────────

  const properties = {
    '이름': {
      title: [{ text: { content: issueTitle } }],
    },
    'GitHub 이슈 번호': {
      number: issueNumber,
    },
    'GitHub URL': {
      url: issueUrl,
    },
  };

  // 상태
  const newStatus = ACTION_TO_STATUS[eventAction];
  if (newStatus) {
    properties['상태'] = { select: { name: newStatus } };
  }

  // 레이블 (multi-select) — 빈 배열도 반영하여 기존 레이블을 지울 수 있도록
  properties['레이블'] = {
    multi_select: labels.map(l => ({ name: l.name })),
  };

  // 유형 (bug/enhancement → 버그/기능 개선)
  const type = detectType(labels, issueTitle);
  properties['유형'] = { select: { name: type } };

  // 관련 도메인 (이슈 본문 파싱)
  const domain = parseAreaFromBody(issueBody);
  if (domain) {
    properties['관련 도메인'] = { select: { name: domain } };
  }

  // 환경 (버그 리포트 본문에서 파싱)
  const env = parseEnvironmentFromBody(issueBody);
  if (env) {
    properties['환경'] = { select: { name: env } };
  }

  // ── 기존 페이지 조회 → 생성 or 업데이트 ───────────────────────────────────

  const existingPage = await findExistingPage(issueNumber);

  if (existingPage) {
    // 업데이트
    await notion.pages.update({
      page_id:    existingPage.id,
      properties,
    });
    console.log(`✅ Notion 페이지 업데이트 완료: #${issueNumber} "${issueTitle}"`);
    console.log(`   페이지 URL: ${existingPage.url}`);
  } else {
    // 신규 생성 — 이슈 본문을 페이지 컨텐츠로 추가
    const bodyBlocks = [];
    if (issueBody) {
      // 본문이 2000자를 넘으면 잘라냄 (Notion API 제한)
      const truncated = issueBody.length > 2000
        ? issueBody.slice(0, 1997) + '...'
        : issueBody;

      bodyBlocks.push({
        object: 'block',
        type:   'paragraph',
        paragraph: {
          rich_text: [{ type: 'text', text: { content: truncated } }],
        },
      });
    }

    const newPage = await notion.pages.create({
      parent:   { database_id: DATABASE_ID },
      properties,
      children: bodyBlocks,
    });

    console.log(`✅ Notion 페이지 생성 완료: #${issueNumber} "${issueTitle}"`);
    console.log(`   페이지 URL: ${newPage.url}`);
  }
}

main().catch(err => {
  console.error('❌ 동기화 실패:', err.message);
  process.exit(1);
});
