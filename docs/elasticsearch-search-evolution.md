# Elasticsearch 검색 적용 기록

이 문서는 PostgreSQL 검색 방식 비교 이후 Elasticsearch를 적용하는 과정에서 확정한
정책, 실제 구현, 실행 검증 및 아직 남아 있는 제한을 기록한다. 기존 H2 검색 과정은
`docs/post-search-evolution.md`, PostgreSQL 이전과 검색 방식 비교는
`docs/postgresql-search-evolution.md`에서 관리한다.

아직 구현하거나 검증하지 않은 Elasticsearch 검색, Opaque cursor 및 장애 fallback은
완료된 기능으로 기록하지 않는다.

## 1. 현재 구조와 Elasticsearch 적용 범위 확정

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
