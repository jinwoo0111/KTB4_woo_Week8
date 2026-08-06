# Elasticsearch 검색 적용 기록

이 문서는 PostgreSQL 검색 방식 비교 이후 Elasticsearch를 적용하는 과정에서 확정한
정책, 실제 구현, 실행 검증 및 아직 남아 있는 제한을 기록한다. 기존 H2 검색 과정은
`docs/post-search-evolution.md`, PostgreSQL 이전과 검색 방식 비교는
`docs/postgresql-search-evolution.md`에서 관리한다.

Elasticsearch document·mapping·analyzer·초기 인덱스와 Alias 기반은 2-1에서 구현했다.
아직 구현하거나 검증하지 않은 실제 검색 query, Opaque cursor, 동기화 및 장애 fallback은
완료된 기능으로 기록하지 않는다.

## 1. 현재 구조와 Elasticsearch 적용 범위 확정

1단계에서는 Elasticsearch 검색 자체를 구현하지 않는다. 기존 구조와 데이터 기준선을
보존하고, 후속 구현에 사용할 책임 경계, 호환 버전, 실행 환경 및 공개 API 계약을
고정한다.

| 하위 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1-1 | 현재 구조와 Elasticsearch 적용 범위 확정 | 완료 |
| 1-2 | Elasticsearch 버전과 실행 환경 확정 | 완료 |
| 1-3 | 백엔드 검색 요청·응답 계약 | 완료 |

### 1-1. 현재 구조와 Elasticsearch 적용 범위 확정

#### 확인한 현재 구조

`PostController#getPosts`가 `GET /posts`에서 일반 목록과 검색 요청을 함께 받고,
`PostService#getPosts`가 검색어 정규화, 범위, 정렬, cursor, 조회 방식 및 응답 조립을
담당한다.

현재 PostgreSQL 경로는 다음과 같다.

- 검색어가 없으면 활성 게시글을 `post_id DESC`로 조회한다.
- 검색어가 있으면 앞뒤 공백 제거, `Locale.ROOT` 소문자 변환 및 2~100자 검증을 한다.
- 검색 범위는 `all`, `title`, `content`이다.
- 기본 검색은 `LOWER(title|content) LIKE '%keyword%'`이다.
- `app.search.mode=fts`일 때 실험용 PostgreSQL FTS 경로를 사용한다.
- 요청 크기보다 한 건 더 조회하여 `has_next`를 판단한다.
- PostgreSQL 페이지네이션은 `post_id < cursor`, `post_id DESC`를 사용한다.
- 모든 목록과 검색은 `deleted_at IS NULL`인 활성 게시글만 반환한다.

게시글 생성·수정·soft delete의 원본 트랜잭션은 PostgreSQL에 있다. 좋아요·댓글 수,
조회수 및 프로필·이미지의 현재값도 PostgreSQL에서 관리한다.

프론트엔드는 현재 무검색 목록과 무한 스크롤만 제공한다. `getPosts`는 `cursor`,
`size`만 전송하고 응답 mapper는 기존 목록 필드만 처리한다. 검색 UI, `keyword`,
`scope`, `sort`, 검색 메타데이터, degraded 안내 및 자동 테스트는 아직 없다.

#### Elasticsearch 적용 범위

검색어가 있는 `GET /posts` 요청에서 다음 책임을 Elasticsearch로 이전할 예정이다.

- 제목과 본문의 한국어 단어 검색
- `all`, `title`, `content` 범위 적용
- 시간순 및 관련도순 후보 ID 탐색
- 관련도순 pagination을 위한 PIT와 `search_after`

PostgreSQL은 계속 다음 책임을 가진다.

- 게시글과 연관 데이터의 유일한 진실 공급원
- 모든 쓰기 트랜잭션과 soft delete
- 검색 결과 ID의 활성 상태 재확인과 현재 응답 데이터 조회
- 검색어가 없는 일반 목록
- Elasticsearch 장애 시 fallback 검색
- Elasticsearch 인덱스 전체 재구축의 원본

Elasticsearch에는 `post_id`, `title`, `content`, `created_at`, `updated_at`의 검색용 최소
projection만 저장할 예정이다. 이미지와 카운터는 검색 조건이 아니므로 색인하지 않는다.

#### 제외 범위

- PostgreSQL 원본 데이터와 쓰기 책임을 Elasticsearch로 이전하지 않는다.
- 상세 조회, 일반 목록, 인증, 댓글, 좋아요 및 조회수를 Elasticsearch로 이전하지 않는다.
- 작성자 검색, 자동완성, 오타 교정, synonym, highlight, 집계 및 개인화 ranking을
  1단계에 포함하지 않는다.
- 다중 노드 HA, Kubernetes, Elastic Cloud 및 Kibana 운영을 현재 Compose 범위에
  포함하지 않는다.
- 기존 PostgreSQL `LIKE`, `pg_trgm`, FTS 결과를 Nori 검색과 의미가 같은 결과로
  간주하지 않는다.

#### 기준 데이터 보존 정책

기존 PostgreSQL canonical dump와 공식 성능 원자료는 변경하지 않는다.

```text
전체 게시글: 100,000건
활성 게시글: 95,000건
삭제 게시글: 5,000건
작성자: 100명
seed: 20260802
COMMON marker: qzcommona91x, 활성 본문 9,500건
RARE marker: tvrarec73z, 활성 본문 95건
canonical dump SHA-256:
e2dbcf795e0b124ac93f210541d49e9e8da93064cc804a779a155e6325c374c6
```

Nori 검색 품질과 성능 실험에는 한국어 키워드를 가진 Elasticsearch용 데이터를 새로
만들되, 기존 canonical의 전체·활성·삭제 및 키워드 빈도 분포는 동일하게 유지한다.
`대한민국 개발자 커뮤니티`처럼 띄어쓰기가 있는 한국어 문장은 별도 품질 fixture로
검증한다. 이 데이터 생성과 측정은 1단계에서 수행하지 않았다.

### 1-2. Elasticsearch 버전과 실행 환경 확정

#### 1-2-A. Elasticsearch 관련 버전과 호환성 확정

Spring Boot dependency management가 실제 선택한 버전을 기준으로 다음처럼 고정했다.

| 구성 요소 | 확정 버전 |
| --- | --- |
| Spring Boot | 4.0.6 |
| Elasticsearch Java Client | 9.2.8 |
| Spring Data Elasticsearch | 6.0.5 |
| Elasticsearch server | 9.2.8 |
| analysis-nori | 9.2.8 |
| Testcontainers | 2.0.5 |

최초 계획의 예상 버전 `9.3.4`는 실제 dependency resolution과 일치하지 않아 사용하지
않았다. 서버와 Nori 이미지는 Java Client와 동일한 `9.2.8`로 정렬했다.

`spring-boot-starter-data-elasticsearch`, Elasticsearch 및 PostgreSQL Testcontainers
의존성을 추가하고 `elasticsearchIntegrationTest` source set과 Gradle task를 만들었다.
현재 Elasticsearch 통합 테스트 클래스는 아직 없어 task 실행 결과는 `NO-SOURCE`이다.
따라서 통합 테스트가 통과했다고 기록하지 않는다.

#### 1-2-B. local·benchmark·production 실행 환경 확정

`docker/elasticsearch/Dockerfile`은
`docker.elastic.co/elasticsearch/elasticsearch:9.2.8`을 기반으로
`analysis-nori`를 비대화식으로 설치한다.

| 환경 | 실행 구성 |
| --- | --- |
| local | 단일 노드, `127.0.0.1:9200`, heap 512m, 전용 volume, 인증 비활성화 |
| benchmark | 단일 노드, `127.0.0.1:9201`, heap 1g, 전용 volume, 인증 비활성화 |
| production | 단일 노드, 호스트 포트 미공개, 내부 `data-network`, heap 1g, 기본 인증 활성화 |

local과 benchmark는 각각 `local`, `benchmark` profile을 사용한다. 기존 production
Compose 호환성을 유지하기 위해 production 서비스는 기본 profile에 남아 있다. 따라서
local 또는 benchmark Elasticsearch만 실행할 때는 `elasticsearch-local` 또는
`elasticsearch-benchmark` 서비스명을 명시한다. 환경 격리는 상호 배타적인 profile이
아니라 서로 다른 서비스, 포트, volume 및 network로 보장한다.

Compose healthcheck는 단일 노드의 기동을 허용하기 위해 `yellow` 이상을 통과 기준으로
사용한다. 1단계 역검증 시 실행 중인 local 노드의 실제 cluster health는 `green`이었다.

#### 1-2 실행 검증 결과

- dependency insight에서 Java Client `9.2.8`, Spring Data Elasticsearch `6.0.5`,
  Testcontainers `2.0.5`를 확인했다.
- local·benchmark·기본 production `docker compose config`가 모두 정상 해석됐다.
- local 실행 노드는 Elasticsearch `9.2.8`, `analysis-nori 9.2.8`, health `green`이었다.
- local `_analyze`에서 `대한민국 개발자 커뮤니티`가 Nori token으로 분해되는 것을
  확인했다.
- benchmark 컨테이너는 호스트 `127.0.0.1:9201`과 전용 volume을 사용한다.
- production 컨테이너는 호스트 port binding이 없고 내부 `data-network`와 전용
  volume을 사용한다.
- production 이전 실행 로그에서 security index 초기화와 cluster `green` 도달을
  확인했다.
- 검증 후 중지한 benchmark와 production 컨테이너의 종료 코드 `143`은 정상적인
  SIGTERM 종료이며 기동 실패로 분류하지 않는다.
- 사용한 커스텀 이미지 ID는
  `sha256:a4ea02b341ea6d88450a8513d77d1221b6351e5a78b1cb18f7aae85ad9d0e5c5`,
  플랫폼은 `linux/arm64`이다.

#### 1-2 변경 파일

- `.dockerignore`
- `.env.example`
- `build.gradle`
- `compose.yaml`
- `docker/elasticsearch/Dockerfile`
- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`
- `src/main/resources/application-benchmark.yaml`
- `src/main/resources/application-prod.yaml`

### 1-3. 백엔드 검색 요청·응답 계약

#### 작업 목적

기존 `GET /posts`의 일반 목록과 PostgreSQL 검색 동작을 유지하면서 Elasticsearch
검색에 필요한 정렬 요청과 검색 상태 메타데이터의 공개 API 계약을 먼저 고정한다.
Elasticsearch 구현 전후에 클라이언트 요청 형식을 다시 변경하지 않고, 요청한 정렬과
실제로 적용된 정렬 및 검색 백엔드를 응답에서 구분할 수 있어야 한다.

#### 완료 체크포인트

- 기존 `keyword`, `scope`, `size` 요청과 일반 목록 동작이 유지되는가
- 검색 정렬값과 검색어가 없는 요청의 금지 조합이 검증되는가
- 기존 숫자 ID cursor를 계속 사용할 수 있는가
- 문자열 cursor가 Controller 요청 DTO에 바인딩되는가
- 일반 목록의 기존 응답 필드와 JSON 구조가 유지되는가
- 검색 응답에 요청 정렬, 실제 정렬, 실제 백엔드 및 기능 저하 여부가 표시되는가
- 아직 구현하지 않은 관련도 검색과 Opaque cursor가 완료된 것처럼 표시되지 않는가
- 단위·Controller·검색 통합·PostgreSQL 통합 테스트가 통과하는가

#### 1-3-A. 현재 API 호환성 경계와 검색 정책 확정

##### 유지하는 기존 계약

검색과 일반 목록은 기존과 동일하게 `GET /posts`를 사용한다. 요청의 `keyword`,
`scope`, `size` 및 응답의 `posts`, `count`, `has_next`, `next_cursor` 필드를 유지한다.

- `keyword`가 없으면 PostgreSQL 일반 목록을 조회한다.
- 일반 목록과 현재 PostgreSQL 검색은 `post_id DESC` 시간순으로 조회한다.
- `scope`는 검색어가 있을 때만 사용할 수 있다.
- 검색어가 없는 일반 목록의 기존 숫자 ID cursor를 계속 지원한다.
- 일반 목록 응답에는 검색 전용 메타데이터를 추가하지 않는다.

##### 확장한 검색 정책

검색 정렬 요청으로 `sort=time|relevance`를 추가하고 기본값은 기존 계약을 보존하는
`time`으로 정했다. 검색어가 없을 때 `sort=relevance`를 사용할 수 없다.

검색 응답에는 다음 상태를 구분하는 `search` 객체를 제공한다.

- 클라이언트가 요청한 정렬
- 실제 검색 결과에 적용한 정렬
- 실제 결과를 생성한 검색 백엔드
- Elasticsearch 장애 등으로 fallback이 발생했는지 여부

Elasticsearch 관련도 검색의 최종 정책은 `_score DESC, post_id DESC`이고,
Elasticsearch 페이지네이션은 PIT와 `search_after`를 포함한 Opaque cursor를 사용하는
것이다. cursor에는 검색 조건을 결합하고 서명을 검증하여 변조나 다른 검색 조건에서의
재사용을 차단할 예정이다. 이 정책은 이번 단계에서 구현하지 않았다.

#### 1-3-B. 검색 요청 DTO와 입력 검증 구현

##### 실제 구현

`PostListRequest`에 `sort`를 추가하고 요청 `cursor` 타입을 `Long`에서 `String`으로
변경했다. 서비스에서 `sort`의 기본값과 허용값을 검증하고, 기존 숫자 문자열 cursor는
`Long` ID cursor로 변환해 기존 Repository에 전달한다.

`InvalidSearchSortException`을 추가하고 공통 예외 처리에서 HTTP 400과
`invalid_search_sort`로 변환한다.

##### 요청 처리 규칙

| 요청 조건 | 처리 결과 |
| --- | --- |
| `keyword` 없음, `sort` 생략 또는 `time` | 기존 PostgreSQL 일반 목록을 시간순으로 조회 |
| `keyword` 없음, `sort=relevance` | HTTP 400, `invalid_search_sort` |
| `keyword` 있음, `sort` 생략 또는 `time` | 기존 PostgreSQL 검색을 시간순으로 조회 |
| `keyword` 있음, `sort=relevance` | 요청 허용, 현재는 기존 PostgreSQL 시간순 검색으로 조회 |
| `sort=TIME`, `RELEVANCE`, `recent` 등 | HTTP 400, `invalid_search_sort` |
| `cursor=100` | 숫자 ID cursor `100`으로 변환하여 조회 |
| `cursor=0` 또는 음수 | HTTP 400, `invalid_pagination_parameter` |
| `cursor=opaque-token` | HTTP 400, `invalid_pagination_parameter` |
| `size=abc` | 요청 바인딩 단계에서 HTTP 400, `invalid_request` |

정렬값은 대소문자를 구분하며 소문자 `time`, `relevance`만 허용한다. 문자열 cursor는
Controller DTO에 바인딩되지만, Opaque cursor codec이 없으므로 서비스에서는 아직
숫자 문자열만 처리한다.

응답의 `next_cursor` 타입은 이번 단계에서 변경하지 않았으며 현재도 숫자 ID이다.

#### 1-3-C. 검색 응답 메타데이터 계약 구현

##### 실제 구현

`PostSearchMetadataResponse`를 추가하고 `PostListResponse`에 nullable `search` 필드를
추가했다. `search`가 `null`이면 JSON에서 필드 자체를 생략하므로 검색어가 없는 일반
목록의 기존 응답 구조는 유지된다.

검색어가 있는 응답의 `search` 객체는 다음 필드를 제공한다.

| 필드 | 의미 |
| --- | --- |
| `requested_sort` | 요청한 정렬. 생략하면 기본값 `time` |
| `effective_sort` | 실제 검색 결과에 적용한 정렬 |
| `backend` | 실제 결과를 생성한 검색 백엔드 |
| `degraded` | Elasticsearch 장애 등으로 fallback이 발생했는지 여부 |

현재 검색은 실제로 PostgreSQL 경로를 사용하므로 검색 응답은
`backend=postgres`, `effective_sort=time`을 반환한다. 현재 기본 설정이 명시적인
PostgreSQL 검색 모드이고 Elasticsearch 실패 후 fallback한 상태는 아니므로
`degraded=false`를 반환한다.

`sort=relevance` 요청도 아직 관련도 검색을 수행하지 않기 때문에 요청값과 실제 적용값을
다음처럼 구분한다.

```json
{
  "message": "posts_success",
  "data": {
    "posts": [],
    "count": 0,
    "has_next": false,
    "next_cursor": null,
    "search": {
      "requested_sort": "relevance",
      "effective_sort": "time",
      "backend": "postgres",
      "degraded": false
    }
  }
}
```

검색어가 없는 일반 목록은 다음처럼 `search` 필드를 포함하지 않는다.

```json
{
  "message": "posts_success",
  "data": {
    "posts": [],
    "count": 0,
    "has_next": false,
    "next_cursor": null
  }
}
```

##### 상태별 메타데이터

| 현재 요청 | `requested_sort` | `effective_sort` | `backend` | `degraded` |
| --- | --- | --- | --- | ---: |
| 검색어 있음, `sort` 생략 | `time` | `time` | `postgres` | `false` |
| 검색어 있음, `sort=time` | `time` | `time` | `postgres` | `false` |
| 검색어 있음, `sort=relevance` | `relevance` | `time` | `postgres` | `false` |
| 검색어 없음 | `search` 객체를 응답에서 생략 |  |  |  |

향후 Elasticsearch 정상 검색은 `backend=elasticsearch`, `degraded=false`로 표시한다.
Elasticsearch 실패로 PostgreSQL fallback이 발생하면 `backend=postgres`,
`effective_sort=time`, `degraded=true`로 표시한다. 이 두 실행 경로는 아직 구현하지
않았다.

#### 변경 파일

##### 운영 코드

- `src/main/java/kr/woo/community/dto/PostListRequest.java`
- `src/main/java/kr/woo/community/dto/PostListResponse.java`
- `src/main/java/kr/woo/community/dto/PostSearchMetadataResponse.java`
- `src/main/java/kr/woo/community/exception/InvalidSearchSortException.java`
- `src/main/java/kr/woo/community/exception/GlobalExceptionHandler.java`
- `src/main/java/kr/woo/community/service/PostService.java`

##### 테스트 코드

- `src/test/java/kr/woo/community/PostControllerTest.java`
- `src/test/java/kr/woo/community/PostServiceTest.java`

#### 1-3-D. 역검증

##### 검증 환경

```text
Spring Boot: 4.0.6
Java toolchain: 21
Gradle: 9.5.1
Gradle launcher JVM: Oracle JDK 26.0.1
OS: macOS 15.7.2, aarch64
기본 검색 backend 설정: postgres
기본 PostgreSQL 검색 mode: like
```

##### 실행한 검증

```bash
git diff --check
./gradlew compileJava compileTestJava
./gradlew test --tests kr.woo.community.PostServiceTest \
  --tests kr.woo.community.PostControllerTest \
  --tests kr.woo.community.PostSearchIntegrationTest
./gradlew test
./gradlew postgresIntegrationTest
```

##### 검증 결과

| 검증 | 결과 |
| --- | --- |
| `git diff --check` | 통과 |
| 운영·테스트 코드 컴파일 | 통과 |
| `PostServiceTest` | 41개 통과, 실패·오류·건너뜀 0개 |
| `PostControllerTest` | 12개 통과, 실패·오류·건너뜀 0개 |
| `PostSearchIntegrationTest` | 5개 통과, 실패·오류·건너뜀 0개 |
| 전체 `test` | 134개 통과, 실패·오류·건너뜀 0개 |
| `postgresIntegrationTest` | 15개 통과, 실패·오류·건너뜀 0개 |

PostgreSQL 통합 검증에는 애플리케이션 동등성 9개, benchmark 데이터 동등성 4개,
FTS 검색 1개 및 `pg_trgm` 검색 동등성 1개가 포함됐다.

최초 sandbox 내부 Gradle 실행은 사용자 Gradle wrapper cache의 lock 파일에 접근하지
못해 실행 전에 실패했다. 이는 코드 또는 테스트 실패 결과가 아니며 공식 검증 결과에
포함하지 않았다. 동일 명령을 허용된 실행 환경에서 다시 실행해 성공한 결과만 위 표에
기록했다.

##### 체크포인트 역검증 결과

- [x] 일반 목록의 PostgreSQL 경로와 기존 숫자 ID cursor가 유지된다.
- [x] 기존 `keyword`, `scope`, `size` 요청이 유지된다.
- [x] `sort` 기본값, 허용값 및 금지 조합이 검증된다.
- [x] 요청 cursor는 문자열로 바인딩되고 기존 숫자 문자열은 ID cursor로 처리된다.
- [x] 일반 목록 응답에는 `search`가 없어 기존 JSON 구조가 유지된다.
- [x] 검색 응답은 요청 정렬과 실제 정렬을 구분한다.
- [x] 검색 응답은 실제 PostgreSQL backend와 비-fallback 상태를 표시한다.
- [x] 관련도 검색과 Opaque cursor를 구현된 기능으로 표시하지 않는다.
- [x] 마지막 응답 변경 이후 전체 테스트와 PostgreSQL 통합 테스트가 통과한다.
- [x] 문서의 요청·응답·오류 규칙이 실제 코드 및 테스트와 일치한다.

#### 남은 제한

- Elasticsearch 검색 조회와 Nori 분석 기반 검색은 아직 구현하지 않았다.
- `sort=relevance`는 요청할 수 있지만 실제 결과는 PostgreSQL 시간순이다.
- 응답 `next_cursor`는 아직 숫자 ID이며 Opaque string이 아니다.
- PIT, `search_after`, cursor codec, 서명, 만료 및 검색 조건 재사용 방지는 아직 없다.
- Elasticsearch 장애 감지, PostgreSQL fallback 및 `degraded=true` 경로는 아직 없다.
- 관련도순 다음 페이지 장애에 대한 `search_temporarily_unavailable` 응답은 아직 없다.
- 프론트엔드의 Opaque cursor 전달, 검색 메타데이터 매핑 및 degraded 안내 UI는 아직 없다.

#### 완료 판정

1-3-A에서 확정한 호환성 경계 안에서 1-3-B 요청 계약과 1-3-C 응답 계약이 서로
충돌하지 않으며, 전체 회귀 및 PostgreSQL 통합 검증을 통과했다. 미구현 범위도 응답과
문서에서 구분했다. 따라서 `1-3. 백엔드 검색 요청·응답 계약`을 완료로 판정한다.

### 1단계 전체 역검증

#### 역검증 순서와 결과

| 역검증 대상 | 확인 결과 |
| --- | --- |
| 1-3 요청·응답 계약 | 기존 일반 목록을 유지하면서 검색 정렬 요청과 실제 backend 메타데이터가 일치함 |
| 1-2 버전·실행 환경 | Client·server·Nori가 9.2.8로 정렬되고 세 환경의 port·volume·network가 분리됨 |
| 1-1 구조·적용 범위 | PostgreSQL 원본 책임, 일반 목록, soft delete 및 canonical 기준선이 유지됨 |
| 1-3 → 1-2 연결 | Elasticsearch 읽기가 기본 비활성화되어 미구현 검색이 기존 요청을 가로채지 않음 |
| 1-2 → 1-1 연결 | 실행 환경 추가가 canonical dump와 기존 PostgreSQL 원자료를 변경하지 않음 |
| 백엔드·프론트 연결 | 일반 목록 JSON 구조가 유지되어 현재 프론트 목록과 무한 스크롤이 계속 사용 가능함 |

#### 최종 실행 검증

```bash
./gradlew dependencyInsight --dependency elasticsearch-java \
  --configuration runtimeClasspath
./gradlew dependencyInsight --dependency spring-data-elasticsearch \
  --configuration runtimeClasspath
./gradlew dependencyInsight --dependency testcontainers \
  --configuration elasticsearchIntegrationTestRuntimeClasspath

docker compose --profile local config
docker compose --profile benchmark config
docker compose config

curl http://127.0.0.1:9200
curl 'http://127.0.0.1:9200/_cat/plugins?format=json'
curl http://127.0.0.1:9200/_cluster/health
curl -X POST http://127.0.0.1:9200/_analyze # Nori 한국어 문장 분석

shasum -a 256 \
  benchmark-data/postgresql/canonical/community-benchmark-100k.dump

./gradlew elasticsearchIntegrationTest
./gradlew test --rerun-tasks
./gradlew postgresIntegrationTest --rerun-tasks
./gradlew test bootJar

# community-react
npm run lint
npm run build
```

| 검증 | 결과 |
| --- | --- |
| 의존성 버전 resolution | Client 9.2.8, Spring Data 6.0.5, Testcontainers 2.0.5 |
| local·benchmark·production Compose 해석 | 통과 |
| local Elasticsearch server·Nori·health | 9.2.8·9.2.8·green |
| Nori 한국어 문장 분석 | 정상 token 생성 확인 |
| canonical dump SHA-256 | 기존 확정값과 일치 |
| Elasticsearch 통합 task | `NO-SOURCE`, 아직 테스트 없음 |
| 백엔드 전체 테스트 강제 재실행 | 134개 통과, 실패·오류·건너뜀 0개 |
| PostgreSQL 통합 테스트 강제 재실행 | 15개 통과, 실패·오류·건너뜀 0개 |
| backend `bootJar` | 통과, 실행 jar 생성 |
| frontend lint | 통과 |
| frontend production build | 통과 |
| backend·frontend `git diff --check` | 통과 |

#### 보정한 불일치와 무효 결과

- 최초 계획의 Elasticsearch `9.3.4` 예상은 실제 Spring Boot 4.0.6 dependency
  resolution과 달라 무효로 분리하고, 실제 선택된 `9.2.8`로 문서와 이미지를 정렬했다.
- Compose healthcheck의 기준은 `yellow` 이상이고 실제 local 검증 결과는 `green`이다.
  healthcheck 설정 자체가 green을 요구한다고 기록하지 않는다.
- `elasticsearchIntegrationTest` task의 exit code는 0이지만 테스트가 없어
  `NO-SOURCE`였다. 이를 Elasticsearch 통합 테스트 통과로 판정하지 않는다.
- 전체 테스트와 PostgreSQL 통합 테스트가 한 차례 Gradle cache의 `UP-TO-DATE` 결과를
  사용했기 때문에 공식 최종 판정 전 `--rerun-tasks`로 모두 강제 재실행했다.
- sandbox의 Gradle cache lock 및 Docker log socket 권한으로 실행 전에 실패한 명령은
  코드 검증 결과에서 제외하고, 허용된 실행 환경에서 다시 성공한 결과만 사용했다.

#### 1단계 완료 체크포인트

- [x] 현재 PostgreSQL 검색 구조와 Elasticsearch 적용·제외 범위를 실제 코드로 확인했다.
- [x] PostgreSQL이 원본·쓰기·일반 목록·활성 상태 검증 책임을 계속 가진다.
- [x] canonical dump와 기존 PostgreSQL 성능 원자료를 변경하지 않았다.
- [x] Elasticsearch용 한국어 데이터는 후속 단계에서 동일 분포로 별도 생성하기로 했다.
- [x] Client, server, Nori 및 관련 라이브러리 버전을 실제 resolution으로 확정했다.
- [x] local, benchmark 및 production Elasticsearch의 실행 자원을 분리했다.
- [x] Nori 플러그인 설치와 실제 한국어 token 분석을 확인했다.
- [x] 기존 `GET /posts` 일반 목록과 숫자 ID cursor 호환성을 유지했다.
- [x] 검색 요청의 `sort`와 문자열 cursor 입력 계약을 구현했다.
- [x] 검색 응답의 실제 backend·정렬·degraded 메타데이터 계약을 구현했다.
- [x] 아직 구현하지 않은 관련도 검색과 Opaque cursor를 완료로 표시하지 않았다.
- [x] 백엔드 전체 회귀, PostgreSQL 통합, backend 패키징 및 frontend 정적 검증이 통과했다.
- [x] 문서와 실제 코드·설정·실행 결과가 일치한다.

#### 1단계 이후로 남긴 작업

- Elasticsearch index mapping, analyzer, alias 및 검색 query 구현
- 한국어 canonical 분포 데이터와 품질 fixture 생성
- Elasticsearch와 PostgreSQL 사이의 색인·동기화 구조
- 실제 `time`, `relevance` 검색과 Opaque cursor 구현
- Elasticsearch 장애 fallback, 503 및 관측성 구현
- Elasticsearch 통합 테스트 작성과 실행
- frontend 검색 요청, 메타데이터 mapper, 검색 UI 및 degraded 안내 구현
- 동일 조건의 검색 품질·성능 측정과 원자료 보존

#### 1단계 완료 판정

1-1, 1-2, 1-3의 모든 완료 체크포인트를 역순으로 검증했고 단계 사이의 설정·API
계약 충돌이 없었다. 전체 회귀 테스트와 정적 검증을 통과했으며, 문서에는 실행하지
않은 Elasticsearch 통합 테스트와 후속 구현을 완료된 결과로 포함하지 않았다.

따라서 `1. 현재 구조와 Elasticsearch 적용 범위 확정`을 완료로 판정한다. 다음 작업은
2단계를 구현하는 것이 아니라, 2단계의 목표와 큰 진행 순서를 먼저 확인하는 것이다.

## 2. Elasticsearch 검색 및 동기화 구조 구현

2단계의 목표는 PostgreSQL을 유일한 원본으로 유지하면서 게시글 검색 projection을
Elasticsearch에 구성하고, 실제 검색과 동기화가 안전하게 연결될 수 있는 기반을 만드는
것이다. 현재는 검색 document, Nori analyzer, 명시적 mapping 및 버전형 인덱스와 Alias
초기화 기반까지 구현했다.

| 하위 단계 | 내용 | 상태 |
| --- | --- | --- |
| 2-1 | 게시글 검색 document·index·Alias 기반 구현 | 완료 |

### 2-1. 게시글 검색 document·index·Alias 기반 구현

#### 작업 목적

PostgreSQL 게시글에서 검색에 필요한 최소 projection만 Elasticsearch document로
정의하고, Nori 분석과 strict mapping을 고정한다. 애플리케이션의 향후 검색·색인 코드는
버전이 붙은 물리 인덱스를 직접 사용하지 않고 read/write Alias를 사용할 수 있어야 한다.
최초 생성과 정상 재실행은 허용하되, 부분 생성이나 계약이 다른 기존 상태는 자동
수정하지 않고 실패시킨다.

#### 완료 체크포인트

- 검색 document가 PostgreSQL 원본의 최소 projection과 일치하는가
- document `_id`, 필드명, nullable 및 timestamp 직렬화 계약이 고정됐는가
- Nori analyzer가 한국어 복합어와 영문 소문자를 실제 Elasticsearch에서 처리하는가
- `dynamic: strict`와 명시적 필드 mapping이 적용되는가
- 계약 외 필드가 실제 색인 요청에서 거부되는가
- 초기 물리 인덱스와 read/write Alias가 한 Create Index 요청으로 생성되는가
- write Alias에만 `is_write_index=true`가 적용되는가
- 정상 상태의 재실행은 변경 없이 통과하는가
- 부분 생성, 잘못된 Alias 연결 및 mapping/settings 변경을 자동 복구 없이 거부하는가
- 기존 백엔드·PostgreSQL 기능과 canonical 원자료가 유지되는가
- 단위·Elasticsearch·PostgreSQL 통합 테스트와 패키징이 통과하는가
- 아직 없는 검색·동기화·자동 초기화 경로를 완료된 기능으로 기록하지 않는가

#### 2-1-A. 게시글 검색 document와 인덱스 계약 확정

##### document 계약

Elasticsearch document에는 검색 후보 탐색과 PostgreSQL hydration에 필요한 다음 필드만
포함한다.

| document 필드 | Java 입력 타입 | Elasticsearch 타입 | nullable | 역할 |
| --- | --- | --- | --- | --- |
| `post_id` | `Long` | `long` | 아니요 | Elasticsearch `_id` 원본과 PostgreSQL hydration ID |
| `title` | `String` | `text` | 아니요 | Nori 제목 검색 |
| `content` | `String` | `text` | 아니요 | Nori 본문 검색 |
| `created_at` | `LocalDateTime` | `date` | 아니요 | PostgreSQL projection 감사와 불일치 확인 |
| `updated_at` | `LocalDateTime` | `date` | 예 | 수정 projection 감사와 불일치 확인 |

Java 모델의 프로퍼티명은 `postId`, `createdAt`, `updatedAt`이고 Elasticsearch JSON
필드명은 `post_id`, `created_at`, `updated_at`이다. Elasticsearch document `_id`는
`String.valueOf(post_id)`로 정했다. `updated_at`이 `null`이면 JSON 필드 자체를
생략한다.

timestamp는 timezone이 없는 ISO local date-time 문자열로 직렬화하고 mapping은
`strict_date_optional_time_nanos`를 사용한다. 현재 timestamp는 검색이나 정렬에
사용하지 않으므로 `index: false`, `doc_values: false`이다.

프로필, 이미지, 좋아요·댓글·조회수 등의 count는 검색 조건이 아니고 자주 변경되므로
document에서 제외한다. 검색 결과의 현재 응답 데이터는 Elasticsearch `_source`가
아니라 PostgreSQL에서 다시 조회한다. PostgreSQL은 게시글의 유일한 진실 공급원이다.

##### 물리 인덱스와 Alias 계약

| 구분 | 이름 | 역할 |
| --- | --- | --- |
| 초기 물리 인덱스 | `community-posts-v000001` | 불변 버전형 index |
| 조회 Alias | `community-posts-read` | 향후 검색 query 대상 |
| 쓰기 Alias | `community-posts-write` | 향후 색인·삭제 대상 |

초기 물리 인덱스는 `number_of_shards: 1`, `number_of_replicas: 0`,
`refresh_interval: 1s`로 정했다. 최초 생성 시 settings, mapping 및 두 Alias를 단일
Create Index 요청에 포함한다. write Alias에만 `is_write_index=true`를 적용한다.

`require_alias=true`는 Alias 자체의 설정이 아니라 실제 색인·삭제 요청에 지정하는
안전장치이다. 이번 하위 단계에는 쓰기 요청 경로가 없으므로 아직 적용하지 않았다.

#### 2-1-B. Nori analyzer와 명시적 mapping 구현

##### 검색 document 구현

`PostSearchDocument`는 `post_id`, `title`, `content`, `created_at`, `updated_at`만
직렬화한다. ID는 양수여야 하고 제목과 본문은 비어 있을 수 없다. PostgreSQL entity
계약에 맞춰 제목은 최대 255자, 본문은 최대 32,000자로 제한한다.

직렬화 예시는 다음과 같다.

```json
{
  "post_id": 123,
  "title": "대한민국 개발자 커뮤니티",
  "content": "한국어 검색을 위한 본문입니다.",
  "created_at": "2026-08-06T15:12:44.011"
}
```

##### analyzer와 mapping 구현

`post-search-index.json`에 settings와 mapping을 고정했다.

- 사용자 정의 tokenizer `community_nori_tokenizer`
- tokenizer type `nori_tokenizer`
- `decompound_mode: mixed`
- `discard_punctuation: true`
- index analyzer `community_nori_index`
- search analyzer `community_nori_search`
- 두 analyzer 모두 `lowercase` filter 사용
- mapping `dynamic: strict`
- `_source.enabled: true`
- 정확히 `post_id`, `title`, `content`, `created_at`, `updated_at`만 선언

index/search analyzer 이름은 향후 서로 다른 구성을 적용할 수 있도록 분리했지만, 현재
구성은 동일하다. 실제 Elasticsearch `_analyze`에서
`대한민국 개발자 커뮤니티 Spring SPRING`을 분석해 `대한민국`, `대한`, `민국`,
`개발자`, `개발`, `자`, `커뮤니티`, `spring` token을 확인했다.

`dynamic: strict`는 `author_profile_image` 같은 계약 외 필드를 포함한 실제 색인 요청을
거부했다. 유효한 `PostSearchDocument`는 `post_id`의 문자열 값을 `_id`로 사용해 실제
인덱스에 저장됐다.

#### 2-1-C. 버전형 인덱스와 read/write Alias 초기화 구현

`PostSearchIndexNames`에 초기 물리 인덱스와 Alias 이름을 고정했다.
`PostSearchIndexDefinition`은 2-1-B JSON 정의를 읽고 최초 생성 요청에 두 Alias를
추가한다. read/write Alias 이름이 비어 있거나 서로 같으면 요청을 만들기 전에
거부한다.

`PostSearchIndexInitializer`의 상태별 동작은 다음과 같다.

| 기존 상태 | 초기화 결과 |
| --- | --- |
| 물리 인덱스와 두 Alias가 모두 없음 | 한 Create Index 요청으로 생성하고 `CREATED` 반환 |
| 물리 인덱스·Alias·mapping·settings가 모두 정상 | 변경 없이 `ALREADY_INITIALIZED` 반환 |
| 물리 인덱스 또는 Alias 일부만 존재 | 실패, 누락 항목을 자동 추가하지 않음 |
| Alias가 예상하지 않은 물리 인덱스에도 연결됨 | 실패, 기존 연결을 변경하지 않음 |
| write Alias의 `is_write_index`가 `true`가 아님 | 실패, 자동 수정하지 않음 |
| mapping 또는 settings가 확정 계약과 다름 | 실패, 자동 수정하지 않음 |

최초 Create Index 응답은 `acknowledged=true`와 `shards_acknowledged=true`를 모두
확인한 경우에만 `CREATED`로 판정한다. 정상 재실행에서는 Alias topology와 함께 strict
mapping, `_source` 비활성화 여부, 필드 타입·analyzer·date 설정 및 shard·replica·refresh
설정을 검증한다.

초기화 구성요소는 현재 명시적으로 호출할 수 있는 코드와 통합 테스트까지 구현했다.
Spring bean 등록, 애플리케이션 시작 시 자동 호출 및 운영용 별도 초기화 명령은 아직
연결하지 않았다. 따라서 애플리케이션을 실행하는 것만으로 실제 local 또는 production
인덱스가 자동 생성된다고 기록하지 않는다.

#### 2-1-D. 역검증

##### 역검증 순서와 결과

| 역검증 대상 | 확인 결과 |
| --- | --- |
| 2-1-C 인덱스·Alias 초기화 | 최초 원자적 생성, 정상 no-op 재실행 및 불완전 상태 fail-closed 확인 |
| 2-1-B analyzer·mapping | 실제 9.2.8 Nori 분석, strict mapping, 계약 외 필드 거부 및 document 색인 확인 |
| 2-1-A document·index 계약 | 필드·nullable·timestamp·`_id`·물리 인덱스·Alias 이름이 구현과 일치함 |
| 2-1-C → 2-1-B 연결 | 초기화가 별도 mapping을 만들지 않고 동일 JSON 정의를 사용함 |
| 2-1-B → 2-1-A 연결 | JSON과 Java 직렬화 필드가 확정한 다섯 필드로 일치함 |
| 2-1 → 1단계 연결 | PostgreSQL 원본 책임과 기존 API 경로를 변경하지 않고 canonical dump를 보존함 |

운영 검색 코드에서 물리 인덱스를 직접 조회하거나 색인하는 경로는 아직 없다. 물리
인덱스 이름은 초기 생성·상태 검증 코드와 통합 테스트에서만 사용한다. 향후 검색과 쓰기
경로는 각각 read/write Alias를 사용해야 한다.

##### 보정한 불일치와 실패 결과

- 최초 2-1-C 구현은 Elasticsearch `get settings` 응답의 값이 `settings.index` 아래에
  중첩되는 구조를 반영하지 않아 정상 재실행 테스트 1개가 실패했다. 응답을 실제 구조로
  정규화한 뒤 재검증했다.
- 역검증에서 초기 Create Index 응답의 acknowledgement 확인이 누락된 것을 발견해
  `acknowledged`와 `shards_acknowledged` 검증을 추가했다.
- Alias가 예상하지 않은 물리 인덱스에도 연결된 상태와 `refresh_interval` 변경 상태를
  fail-closed로 처리하는 통합 테스트를 추가했다.
- `_source.enabled=true`는 Elasticsearch 기본값이므로 mapping 조회 응답에서 명시값이
  생략돼 `null`로 반환됐다. 이를 반드시 `true`로 반환해야 한다고 작성한 최초 역검증
  테스트 2개가 실패했다. 의미가 같은 `null` 또는 `true`는 허용하고 명시적 `false`만
  거부하도록 보정했다.
- 위 실패 결과는 최종 성공 결과와 분리했다. 보정 후 전체 Elasticsearch 통합 테스트를
  강제 재실행했다.

##### 최종 실행 환경과 검증

```text
Spring Boot: 4.0.6
Java toolchain: 21
Elasticsearch Java Client: 9.2.8
Elasticsearch server: 9.2.8
analysis-nori: 9.2.8
Testcontainers: 2.0.5
```

실행한 최종 검증은 다음과 같다.

```bash
git diff --check
shasum -a 256 \
  benchmark-data/postgresql/canonical/community-benchmark-100k.dump
./gradlew compileJava compileTestJava compileElasticsearchIntegrationTestJava
./gradlew elasticsearchIntegrationTest --rerun-tasks
./gradlew test bootJar --rerun-tasks
./gradlew postgresIntegrationTest --rerun-tasks
```

| 검증 | 결과 |
| --- | --- |
| `git diff --check` | 통과 |
| canonical dump SHA-256 | `e2dbcf795e0b124ac93f210541d49e9e8da93064cc804a779a155e6325c374c6`, 기존값과 일치 |
| 운영·단위·Elasticsearch 통합 테스트 코드 컴파일 | 통과 |
| `PostSearchDocumentTest` | 3개 통과, 실패·오류·건너뜀 0개 |
| Elasticsearch 통합 테스트 | 11개 통과, 실패·오류·건너뜀 0개 |
| 전체 `test` | 137개 통과, 실패·오류·건너뜀 0개 |
| `postgresIntegrationTest` | 15개 통과, 실패·오류·건너뜀 0개 |
| `bootJar` | 통과, 실행 jar 생성 |

Elasticsearch 통합 테스트 11개는 document/mapping/analyzer 4개와 초기화·Alias 상태
검증 7개로 구성된다. PostgreSQL 통합 테스트는 애플리케이션 동등성 9개, benchmark
데이터 동등성 4개, FTS 1개 및 `pg_trgm` 동등성 1개이다.

##### 변경 파일

운영 코드와 설정:

- `src/main/java/kr/woo/community/search/document/PostSearchDocument.java`
- `src/main/java/kr/woo/community/search/index/PostSearchIndexDefinition.java`
- `src/main/java/kr/woo/community/search/index/PostSearchIndexInitializationResult.java`
- `src/main/java/kr/woo/community/search/index/PostSearchIndexInitializer.java`
- `src/main/java/kr/woo/community/search/index/PostSearchIndexNames.java`
- `src/main/java/kr/woo/community/search/index/PostSearchIndexStateException.java`
- `src/main/resources/elasticsearch/post-search-index.json`

테스트 코드:

- `src/test/java/kr/woo/community/search/document/PostSearchDocumentTest.java`
- `src/elasticsearchIntegrationTest/java/kr/woo/community/search/ElasticsearchTestcontainersConfiguration.java`
- `src/elasticsearchIntegrationTest/java/kr/woo/community/search/PostSearchIndexDefinitionIntegrationTest.java`
- `src/elasticsearchIntegrationTest/java/kr/woo/community/search/PostSearchIndexInitializerIntegrationTest.java`

##### 완료 체크포인트 역검증 결과

- [x] 검색 document가 PostgreSQL 검색용 최소 projection으로 제한된다.
- [x] `_id`, JSON 필드명, nullable 및 timestamp 직렬화 계약이 구현과 일치한다.
- [x] 실제 Nori 환경에서 한국어 복합어와 영문 lowercase token을 확인했다.
- [x] strict mapping과 계약 외 필드 거부를 실제 색인 요청으로 검증했다.
- [x] 초기 물리 인덱스와 두 Alias가 단일 Create Index 요청으로 생성된다.
- [x] write Alias에만 `is_write_index=true`가 적용된다.
- [x] 정상 상태의 재실행은 변경 없이 통과한다.
- [x] 부분 생성, 잘못된 Alias topology 및 mapping/settings drift를 자동 수정 없이 거부한다.
- [x] 마지막 보정 후 Elasticsearch·백엔드·PostgreSQL 전체 검증과 패키징이 통과한다.
- [x] canonical dump와 기존 PostgreSQL 검색·API 동작을 변경하지 않았다.
- [x] 문서가 실제 구현, 실행 결과, 중간 실패 및 미구현 제한과 일치한다.

#### 남은 제한

- 실제 Elasticsearch 검색 query와 read Alias를 사용하는 Repository는 아직 없다.
- write Alias를 사용하는 색인·삭제 gateway와 `require_alias=true` 요청은 아직 없다.
- `PostSearchDocument`를 PostgreSQL entity/projection에서 생성하는 mapper가 아직 없다.
- 생성·수정·soft delete를 Elasticsearch에 전달하는 동기화 구조가 아직 없다.
- 최초 인덱스 초기화는 애플리케이션 시작이나 운영 명령에 자동 연결되지 않았다.
- 신규 버전 인덱스 생성, 전체 재색인 및 `_aliases` atomic swap은 아직 없다.
- 한국어 canonical 분포 데이터 생성과 검색 품질·성능 측정은 아직 수행하지 않았다.
- 검색 결과의 PostgreSQL active-only hydration은 아직 구현하지 않았다.

#### 완료 판정

2-1-A의 document·인덱스 계약, 2-1-B의 Nori analyzer·strict mapping, 2-1-C의 초기
물리 인덱스·read/write Alias 초기화가 동일한 필드와 이름 계약으로 연결된다. 역검증에서
발견한 acknowledgement와 검증 근거 누락을 보정했고, 마지막 변경 이후 실제
Elasticsearch 9.2.8 + Nori 통합 테스트와 전체 회귀 테스트를 통과했다.

검색 query, 쓰기 gateway, 동기화 및 자동 초기화는 완료 범위와 명확히 분리했다. 따라서
`2-1. 게시글 검색 document·index·Alias 기반 구현`을 완료로 판정한다.
