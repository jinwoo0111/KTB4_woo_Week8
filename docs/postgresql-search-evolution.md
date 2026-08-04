# PostgreSQL 검색 고도화 기록

이 문서는 H2 기반 게시글 검색의 성능 한계를 확인한 뒤 PostgreSQL로 데이터베이스를
이전하고, 동일한 데이터와 부하 조건에서 `LIKE '%keyword%'`, `pg_trgm`, PostgreSQL
Full Text Search를 비교하는 과정을 기록한다.

기존 검색 기능의 요구사항, API, H2 검색 구현, 대량 데이터 생성 및 H2 성능 기준선은
`docs/post-search-evolution.md`에서 관리한다. 이 문서는 기존 문서의 7단계에 내용을
추가하지 않고 PostgreSQL 이전부터 별도의 기록으로 관리한다.

학습을 위한 질문과 답변 및 이해 확인 과정은 이 문서에 포함하지 않는다. 이 문서에는
프로젝트에서 결정한 정책, 구현 결과, 실행 검증 및 성능 비교에 필요한 재현 정보만
남긴다.

## 1. PostgreSQL 이전 배경과 진행 원칙

### 1.1. H2 검색 성능의 한계

기존 게시글 검색은 H2에서 다음 방식으로 동작한다.

```sql
LOWER(column) LIKE '%keyword%'
```

게시글 수를 1천 건, 1만 건, 10만 건으로 늘리며 단일 사용자 성능을 측정한 결과,
10만 건에서 응답 시간 p50이 약 2.88초까지 증가했다.

초당 50건의 요청을 목표로 수행한 부하 테스트 결과는 다음과 같다.

| 지표 | 결과 |
| --- | ---: |
| 목표 요청률 | 50 RPS |
| 응답 시간 p50 | 약 17.08초 |
| 응답 시간 p95 | 약 19.80초 |
| 실제 처리량 | 약 2.71 RPS |
| 시작되지 못한 요청 | 2,791건 |

따라서 현재 H2의 `LOWER(column) LIKE '%keyword%'` 방식은 10만 건 규모에서 운영용
검색 기능으로 사용하기 어렵다고 판단했다.

### 1.2. PostgreSQL 이전 후 비교 대상

PostgreSQL로 데이터베이스를 이전한 사실만으로 최종 검색 방식을 결정하지 않는다.
동일한 10만 건 데이터와 동일한 부하 조건에서 다음 방식을 비교한다.

1. PostgreSQL의 기본 `LIKE '%keyword%'`
2. `pg_trgm` 인덱스를 적용한 부분 문자열 검색
3. PostgreSQL Full Text Search를 적용한 단어 및 관련도 기반 검색

`pg_trgm`은 기존 부분 문자열 검색의 의미를 유지하면서 성능을 개선할 수 있는지
확인한다. Full Text Search는 단어 기반 검색, 관련도 검색 및 결과 특성이 기존 부분
문자열 검색과 어떻게 다른지 확인한다.

최종 검색 방식은 다음 항목을 함께 측정한 뒤 결정한다.

- 실행 계획
- 단일 요청 지연 시간
- 부하 상황의 p50 및 p95
- 실제 처리량
- 요청 누락 여부
- 검색 결과의 의미와 품질
- 인덱스 크기
- 쓰기 및 데이터 생성 비용
- 운영 복잡도

### 1.3. 현재 진행 범위

PostgreSQL 이전은 다음 순서로 진행한다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1-1 | PostgreSQL 이전 정책과 환경 설계 | 완료 |
| 1-2 | PostgreSQL 실행 환경과 스키마 기반 구축 | 완료 |
| 1-3 | 기존 기능과 benchmark 데이터 동등성 검증 | 완료 |
| 1-4 | 재현성과 전체 회귀 검증 및 LIKE 기준선 측정 준비 | 완료 |

검색 방식별 `LIKE`, `pg_trgm`, Full Text Search 구현과 성능 측정은 PostgreSQL 이전
기반과 재현 가능한 benchmark dataset을 완성한 뒤 진행한다.

## 2. 1-1. PostgreSQL 이전 정책과 환경 설계

### 2.1. PostgreSQL 실행 환경 정책

#### PostgreSQL 버전

PostgreSQL 버전과 Docker 이미지는 다음과 같이 고정했다.

```text
PostgreSQL: 18.4
Docker Official Image: postgres:18.4
```

`latest` 태그를 사용하면 버전 변경에 따라 버그 수정, 옵티마이저 및 기본 동작이 달라질
수 있다. 이 경우 성능 수치의 차이가 검색 방식 때문인지 PostgreSQL 버전 변경 때문인지
구분하기 어려우므로 명시적인 버전 태그를 사용한다.

공식 성능 측정 시에는 태그뿐 아니라 실제 사용한 image digest도 기록한다. 최초 실행
환경 검증에서 확인한 이미지 정보는 다음과 같다.

```text
image: postgres:18.4
image ID: sha256:3a82e1f56c8f0f5616a11103ac3d47e632c3938698946a7ad26da0df1334744a
OS/architecture: linux/arm64
```

#### local·benchmark 분리

일반 개발용 PostgreSQL과 성능 측정용 PostgreSQL을 서로 다른 컨테이너로 분리한다.

```text
Docker Compose project
├── postgres-local
│   ├── database: community
│   ├── user: community_local
│   ├── host port: 5432
│   └── local 전용 volume
│
└── postgres-benchmark
    ├── database: community_benchmark
    ├── user: community_benchmark
    ├── host port: 5433
    └── benchmark 전용 volume
```

두 컨테이너는 서로 다른 데이터베이스, 사용자, 포트 및 named volume을 사용한다.
공식 benchmark를 실행할 때는 호스트 자원 경쟁을 줄이기 위해 `postgres-local`을
중지하고 `postgres-benchmark`만 실행한다.

컨테이너 분리는 데이터와 실행 상태를 격리하지만 CPU, 메모리 및 디스크와 같은 호스트
자원까지 물리적으로 분리하는 것은 아니다. 공식 성능 비교에서는 실행 중인 다른
프로세스와 컨테이너 상태도 동일하게 유지한다.

#### 데이터 영속성

두 PostgreSQL 컨테이너는 각각 독립적인 named volume을 사용한다.

```text
community-postgres-local-data
└── 일반 개발 데이터

community-postgres-benchmark-data
└── benchmark 데이터
```

PostgreSQL 18 Docker 이미지의 데이터 경로 정책에 맞춰 volume은 컨테이너의
`/var/lib/postgresql`에 연결한다.

#### 인코딩과 locale

두 환경은 동일한 초기화 옵션을 사용한다.

```text
encoding: UTF8
locale provider: builtin
builtin locale: PG_UNICODE_FAST
```

문자열 비교와 검색 결과 및 성능 비교 조건이 환경마다 달라지지 않도록 local과
benchmark에 같은 값을 적용한다.

#### 비밀 정보

실제 비밀번호는 Git에서 추적하지 않는 `.env`에 저장한다. 저장소에는 필요한 변수의
형식만 제공하는 `.env.example`을 포함한다.

```text
.env
└── 실제 local·benchmark 비밀번호, JWT secret

.env.example
└── 변수 이름과 기본 구조
```

개발용 PostgreSQL 비밀번호는 loopback 개발 환경에서만 사용하며 운영 환경에서
재사용하지 않는다. 운영 비밀번호는 배포 환경의 secret 또는 프로세스 환경 변수로
주입한다.

### 2.2. 스키마·애플리케이션 정책

#### 스키마 관리 주체

PostgreSQL 스키마의 생성 및 변경 책임을 다음과 같이 분리한다.

```text
Flyway
└── 실제 스키마 생성과 변경의 유일한 주체

Hibernate
└── 엔티티와 실제 스키마의 일치 여부 검증

schema.sql / data.sql
└── 사용하지 않음
```

PostgreSQL 스키마는 Flyway versioned migration으로 변경한다. Hibernate는
`ddl-auto=validate`를 사용해 엔티티 매핑과 실제 스키마의 불일치를 애플리케이션
시작 시 검증한다.

#### 기본 migration

첫 migration은 다음 파일로 관리한다.

```text
V1__create_base_schema.sql
```

V1에는 현재 애플리케이션 실행에 필요한 기본 구조만 포함한다.

```text
users, posts, comments, post_likes
엔티티별 ID sequence
primary key
foreign key
unique 제약
check 제약
nullability
기본값
일반 애플리케이션 조회용 인덱스
```

다음 검색 후보 구조는 V1에서 제외한다.

```text
pg_trgm extension
trigram GIN·GiST 인덱스
tsvector 컬럼
Full Text Search 인덱스
관련도 검색 구조
검색 후보에만 필요한 함수
```

#### 실험 DDL과 최종 migration 분리

검색 후보를 실험하는 과정에서는 후보 DDL을 Flyway versioned migration에 바로
누적하지 않는다.

```text
Flyway versioned migration
└── 확정된 애플리케이션 스키마만 관리

검색 후보 실험 SQL
├── pg_trgm 적용·검증·제거
└── Full Text Search 적용·검증·제거

최종 검색 방식 결정 후
└── 선택한 DDL만 Flyway V2로 승격
```

이 정책은 최종적으로 선택하지 않은 검색 구조가 운영 migration history에 남는 것을
방지한다.

#### 기본키 생성 전략

기존 엔티티의 `AUTO` 기본키 전략은 PostgreSQL의 명시적인 sequence 전략으로
변경한다.

```text
User     → users_seq
Post     → posts_seq
Comment  → comments_seq
PostLike → post_likes_seq
```

Hibernate의 `allocationSize`와 PostgreSQL sequence의 증가 단위는 모두 50으로
일치시킨다.

```text
JPA @SequenceGenerator allocationSize: 50
PostgreSQL sequence INCREMENT BY: 50
```

이는 PostgreSQL이 지원하는 sequence 기반 ID 생성과 Hibernate batching을 함께
사용하기 위한 정책이다. ID는 유일성을 보장하지만 빈틈없는 연속 번호는 보장하지
않는다.

### 2.3. 테스트·benchmark·재현성 정책

#### 테스트 DB 역할 분리

테스트는 다음 역할에 따라 DB를 분리한다.

```text
빠른 일반 회귀 테스트
├── Mock
└── H2

PostgreSQL 호환성·스키마·검색 테스트
└── Testcontainers PostgreSQL 18.4

공식 성능 테스트
└── 고정된 postgres-benchmark 컨테이너
```

H2를 완전히 제거하지 않지만 PostgreSQL 고유 동작을 검증해야 하는 테스트를 H2에
맡기지 않는다.

계획한 테스트 실행 경로는 다음과 같다.

```bash
# 빠른 회귀 테스트
./gradlew test

# PostgreSQL 통합 테스트
./gradlew postgresIntegrationTest

# 전체 검증
./gradlew test postgresIntegrationTest
```

`postgresIntegrationTest`와 Testcontainers 기반 검증은 1-3에서 구현했다.

#### benchmark 데이터 재현 정책

benchmark generator는 다음 값을 기준으로 사용한다.

```text
post-count: 100000
author-count: 100
seed: 20260802
persistence-batch-size: 1000
```

검색 방식마다 generator를 다시 실행하면 ID, `created_at`, marker 위치 및 sequence
상태가 달라질 수 있다. 따라서 세 검색 방식의 공식 비교에는 하나의 canonical
dataset을 사용한다.

```text
1. V1 상태의 PostgreSQL에 10만 건을 한 번 생성한다.
2. 데이터 건수와 marker 분포를 검증한다.
3. 검증된 데이터를 canonical dataset으로 확정한다.
4. PostgreSQL data-only dump를 만든다.
5. dump checksum을 기록한다.
6. LIKE·pg_trgm·FTS 실험은 같은 dump에서 복원한다.
7. 복원 후 ANALYZE를 수행한다.
```

dump에는 애플리케이션 데이터와 sequence 상태를 포함하되
`flyway_schema_history`는 포함하지 않는다. 스키마는 Flyway가 생성하고 데이터와
sequence 상태는 canonical dump가 복원하는 역할로 분리한다.

공식 성능 비교에서는 데이터뿐 아니라 다음 조건도 동일하게 유지한다.

- PostgreSQL 이미지와 digest
- 애플리케이션 빌드
- JVM 및 실행 옵션
- connection pool 설정
- warm-up과 measurement 시간
- k6 시나리오와 목표 요청률
- local PostgreSQL 중지 여부
- 복원 후 통계 정보

## 3. 1-2. PostgreSQL 실행 환경과 스키마 기반 구축

### 3.1. PostgreSQL Docker Compose 실행 환경

저장소 루트에 `compose.yaml`을 추가하고 local과 benchmark를 Compose profile로
분리했다.

```yaml
services:
  postgres-local:
    image: postgres:18.4
    profiles:
      - local

  postgres-benchmark:
    image: postgres:18.4
    profiles:
      - benchmark
```

실행 명령은 다음과 같다.

```bash
# local PostgreSQL
docker compose --profile local up -d postgres-local

# benchmark PostgreSQL
docker compose --profile benchmark up -d postgres-benchmark
```

두 서비스를 모두 정의했지만 profile을 지정하지 않은 `docker compose up -d`에서는
PostgreSQL 서비스를 자동 실행하지 않는다.

#### 포트와 네트워크

local과 benchmark는 다음 주소에만 공개한다.

```text
local:     127.0.0.1:5432
benchmark: 127.0.0.1:5433
```

Compose 포트 구성은 다음과 같다.

```yaml
ports:
  - "127.0.0.1:${LOCAL_POSTGRES_PORT:-5432}:5432"
```

```yaml
ports:
  - "127.0.0.1:${BENCHMARK_POSTGRES_PORT:-5433}:5432"
```

#### health check

두 서비스에 `pg_isready` 기반 health check를 적용했다.

```yaml
healthcheck:
  test:
    - CMD-SHELL
    - pg_isready -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}"
  interval: 5s
  timeout: 5s
  retries: 10
  start_period: 10s
```

실제 실행에서 두 컨테이너가 `healthy` 상태에 도달하고 설정된 DB와 사용자로 연결을
받을 수 있는 것을 확인했다.

#### profile별 비밀번호 처리

Compose profile은 서비스 선택 전에 YAML 환경 변수를 먼저 해석한다. `${VAR:?}`를
사용하면 local profile만 실행해도 비활성 benchmark 서비스의 비밀번호가 없다는 이유로
실패했다.

profile별 설정 독립성을 유지하기 위해 비밀번호에는 빈 기본값을 사용한다.

```yaml
POSTGRES_PASSWORD: ${LOCAL_POSTGRES_PASSWORD:-}
```

```yaml
POSTGRES_PASSWORD: ${BENCHMARK_POSTGRES_PASSWORD:-}
```

비활성 profile의 비밀번호는 선택한 서비스 실행을 막지 않는다. 활성 PostgreSQL
서비스의 비밀번호가 비어 있으면 PostgreSQL 공식 이미지가 DB 초기화 전에 다음 오류로
종료한다.

```text
Database is uninitialized and superuser password is not specified.
```

실제 검증에서 비밀번호가 없는 활성 컨테이너는 `Exited (1)`로 종료됐고 데이터베이스는
초기화되지 않았다.

### 3.2. 애플리케이션 의존성·프로필·환경 변수 전환

#### Gradle 의존성

일반 실행 환경의 H2 의존성을 PostgreSQL과 Flyway로 전환했다.

```gradle
implementation 'org.springframework.boot:spring-boot-starter-flyway'

runtimeOnly 'org.flywaydb:flyway-database-postgresql'
runtimeOnly 'org.postgresql:postgresql'

testRuntimeOnly 'com.h2database:h2'
```

H2는 빠른 테스트의 runtime에서만 사용한다. 생성된 운영 JAR에는 PostgreSQL JDBC와
Flyway가 포함되고 H2는 포함되지 않는다.

검증한 주요 JAR은 다음과 같다.

```text
postgresql-42.7.10.jar
flyway-core-11.14.1.jar
flyway-database-postgresql-11.14.1.jar
spring-boot-flyway-4.0.6.jar
```

#### 공통 스키마 관리 설정

공통 `application.yaml`에서 H2 DataSource를 제거하고 다음 정책을 적용했다.

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration

  sql:
    init:
      mode: never

  jpa:
    hibernate:
      ddl-auto: validate
```

애플리케이션 시작 순서는 다음과 같다.

```text
PostgreSQL DataSource 생성
    ↓
Flyway migration 실행 및 검증
    ↓
Hibernate가 엔티티와 스키마 검증
    ↓
일치하면 애플리케이션 시작 완료
```

#### local DataSource

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:${LOCAL_POSTGRES_PORT:5432}/${LOCAL_POSTGRES_DB:community}
    username: ${LOCAL_POSTGRES_USER:community_local}
    password: ${LOCAL_POSTGRES_PASSWORD}
```

#### benchmark DataSource

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:${BENCHMARK_POSTGRES_PORT:5433}/${BENCHMARK_POSTGRES_DB:community_benchmark}
    username: ${BENCHMARK_POSTGRES_USER:community_benchmark}
    password: ${BENCHMARK_POSTGRES_PASSWORD}
```

benchmark 프로필의 기존 batching 설정은 유지했다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
        order_inserts: true
```

#### prod DataSource

운영 환경은 외부 PostgreSQL 연결 정보를 환경 변수로 전달한다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT:5432}/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
```

운영 연결 정보와 비밀번호에는 저장소 기본값을 제공하지 않는다.

#### 빠른 테스트 설정

test 프로필은 H2와 Hibernate `create-drop`을 유지하고 Flyway를 비활성화한다.

```properties
spring.datasource.url=jdbc:h2:mem:community-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.flyway.enabled=false
spring.jpa.hibernate.ddl-auto=create-drop
```

PostgreSQL migration과 PostgreSQL 고유 동작은 1-3의 Testcontainers 통합 테스트에서
자동 검증한다.

#### H2 Console과 Dockerfile 정리

일반 실행 환경에서 H2를 제거하면서 다음 설정도 제거했다.

```text
spring-boot-h2console 의존성
spring.h2.console 설정
/h2-console/** Spring Security 허용
H2 Console을 위한 frameOptions.sameOrigin
Dockerfile의 /app/data 디렉터리
Dockerfile의 DB_PATH
```

애플리케이션 컨테이너는 업로드 파일만 직접 보관하고 PostgreSQL 데이터는 PostgreSQL
컨테이너의 named volume에서 관리한다.

### 3.3. Flyway V1 스키마·엔티티 sequence 구현

#### V1 구조

기본 스키마 migration을 다음 위치에 추가했다.

```text
src/main/resources/db/migration/V1__create_base_schema.sql
```

V1 구조는 다음과 같다.

```text
V1__create_base_schema.sql
├── sequence 4개
│   ├── users_seq
│   ├── posts_seq
│   ├── comments_seq
│   └── post_likes_seq
│
├── table 4개
│   ├── users
│   ├── posts
│   ├── comments
│   └── post_likes
│
├── primary key
├── foreign key
├── unique 제약
├── check 제약
├── nullability
├── counter 기본값
└── 일반 애플리케이션 조회용 인덱스
```

#### users

`users`는 이메일과 닉네임의 유일성을 DB에서 보장하고 역할 값의 범위를 제한한다.

```text
pk_users
uk_users_email
uk_users_nickname
ck_users_role: USER 또는 ADMIN
```

기존 엔티티의 `unique=true`로 Hibernate가 이메일 유일성을 표현하고 있었지만 스키마
생성 주체가 Flyway로 변경됐으므로 V1에 이름이 명확한 UNIQUE 제약을 직접 선언했다.
서비스가 이미 중복을 거부하는 닉네임도 동시 요청에서 유일성을 보장할 수 있도록
UNIQUE 제약을 적용했다.

#### posts

`posts`는 작성자 외래키와 음수가 될 수 없는 counter 제약을 가진다.

```text
pk_posts
fk_posts_user
ck_posts_like_count
ck_posts_comment_count
ck_posts_view_count
```

counter 컬럼은 다음 조건을 사용한다.

```text
like_count:    INTEGER NOT NULL DEFAULT 0
comment_count: INTEGER NOT NULL DEFAULT 0
view_count:    INTEGER NOT NULL DEFAULT 0
```

게시글 본문은 엔티티의 `length=32_000`과 일치하도록 `VARCHAR(32000)`을 사용한다.

#### comments

`comments`는 게시글과 작성자에 대한 외래키를 가진다.

```text
pk_comments
fk_comments_post
fk_comments_user
```

#### post_likes

`post_likes`는 동일한 사용자가 동일한 게시글에 좋아요를 두 번 생성하지 못하도록
복합 UNIQUE 제약을 사용한다.

```text
uk_post_likes_post_user
└── UNIQUE (post_id, user_id)
```

애플리케이션의 사전 중복 검사는 일반적인 중복 요청을 도메인 오류로 변환하고, DB
UNIQUE 제약은 동시 요청에서도 중복 행이 저장되는 것을 최종 차단한다.

#### 외래키 삭제 정책

회원, 게시글 및 댓글은 `deleted_at`을 사용하는 soft delete 방식이므로 외래키에
`ON DELETE CASCADE`를 적용하지 않는다. PostgreSQL의 기본 `NO ACTION` 동작을 사용해
참조되는 부모 행의 물리 삭제를 차단한다.

```text
회원 삭제
└── users.deleted_at 설정

게시글 삭제
├── posts.deleted_at 설정
└── 연결된 comments.deleted_at 설정

댓글 삭제
└── comments.deleted_at 설정
```

#### 일반 조회용 인덱스

V1에는 검색 후보 인덱스가 아닌 현재 애플리케이션 관계 조회와 목록 조회에 필요한
인덱스만 포함한다.

```text
idx_posts_user_id
idx_posts_active_cursor
idx_comments_user_id
idx_comments_active_post
idx_post_likes_user_id
```

삭제되지 않은 게시글의 ID 기반 cursor pagination은 다음 partial index를 사용한다.

```sql
CREATE INDEX idx_posts_active_cursor
    ON posts (post_id DESC)
    WHERE deleted_at IS NULL;
```

삭제되지 않은 댓글 목록 조회는 다음 partial index를 사용한다.

```sql
CREATE INDEX idx_comments_active_post
    ON comments (post_id, comment_id)
    WHERE deleted_at IS NULL;
```

#### 엔티티 sequence 매핑

네 엔티티에 독립적인 `@SequenceGenerator`를 적용했다.

```java
@GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "users_seq_generator"
)
@SequenceGenerator(
        name = "users_seq_generator",
        sequenceName = "users_seq",
        allocationSize = 50
)
```

V1의 sequence도 동일한 증가 단위를 사용한다.

```sql
CREATE SEQUENCE users_seq
    START WITH 1
    INCREMENT BY 50;
```

실제 PostgreSQL 저장 검증에서 첫 애플리케이션 프로세스는 사용자 ID 1과 2를
발급했다. 애플리케이션 재시작 후 생성한 사용자는 ID 52를 받았고 sequence의
`last_value`는 101이 됐다. 이는 `allocationSize=50`에서 애플리케이션 종료 시 사용하지
않은 ID 범위가 남을 수 있는 정상 동작이다.

### 3.4. 빈 DB 초기화·재시작·애플리케이션 부팅 검증

#### 검증 시작 조건

검증 전 local과 benchmark 컨테이너 및 named volume이 존재하지 않는 것을 확인했다.
PostgreSQL 컨테이너를 처음 실행한 뒤 애플리케이션 시작 전 `public` 스키마의 테이블
수는 0이었다.

```text
public_table_count = 0
```

#### local 최초 부팅

빈 local DB에서 확인한 실행 순서는 다음과 같다.

```text
PostgreSQL 18.4 연결
    ↓
Flyway가 빈 public 스키마 확인
    ↓
flyway_schema_history 생성
    ↓
V1 적용
    ↓
Hibernate validate 통과
    ↓
EntityManagerFactory 초기화
    ↓
Tomcat 시작
```

확인된 Flyway 로그는 다음과 같다.

```text
Current version of schema "public": << Empty Schema >>
Migrating schema "public" to version "1 - create base schema"
Successfully applied 1 migration to schema "public", now at version v1
```

애플리케이션 health endpoint는 `HTTP 200`을 반환했다.

#### PostgreSQL·애플리케이션 재시작

local DB에 사용자 두 명을 저장한 뒤 애플리케이션을 graceful shutdown하고 PostgreSQL
컨테이너를 재시작했다. named volume은 유지했다.

재시작 후 Flyway 로그는 다음과 같았다.

```text
Successfully validated 1 migration
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.
```

재시작 전후 Flyway 상태는 다음과 같다.

| 항목 | 최초 부팅 후 | 재시작 후 |
| --- | ---: | ---: |
| history 행 수 | 1 | 1 |
| version | 1 | 1 |
| checksum | `1692416606` | `1692416606` |
| V1 재실행 | 해당 없음 | 실행되지 않음 |

기존 사용자 ID 1과 2 및 `users_seq.last_value=51`도 재시작 후 유지됐다. 재시작한
애플리케이션에서 새 사용자를 저장했을 때 ID 52가 충돌 없이 생성됐다.

#### benchmark 독립 초기화

local을 중지하고 새로운 benchmark named volume을 생성했다. benchmark 애플리케이션
실행 전 `public` 스키마의 테이블 수는 0이었고, benchmark 프로필 실행 후 V1이
독립적으로 적용됐다.

```text
database: community_benchmark
Flyway version: 1
Flyway checksum: 1692416606
application table count: 4
user count: 0
```

benchmark를 중지하고 기존 local volume을 다시 연결했을 때 local 사용자 3건, Flyway
history 및 sequence 상태가 그대로 유지됐다. 이를 통해 두 환경의 스키마 이력, 데이터
및 sequence 현재 상태가 서로 격리됐음을 확인했다.

#### 실제 `.env` 최종 검증

후속 역검증에서는 임시 환경 파일이 아니라 실제 `.env`를 사용해 두 profile을 각각
실행했다.

```text
local
├── 별도 password 명령행 인자 없이 PostgreSQL 연결
├── V1 적용 및 Hibernate validate 통과
└── health HTTP 200

benchmark
├── 별도 password 명령행 인자 없이 PostgreSQL 연결
├── V1 적용 및 Hibernate validate 통과
└── health HTTP 200
```

실제 비밀번호 값은 문서 및 Git에 기록하지 않는다.

### 3.5. 1-2 최종 검증 결과

1-2 구현 후 다음 검증을 통과했다.

```bash
./gradlew test bootJar
```

```text
BUILD SUCCESSFUL
```

생성된 JAR에 다음 항목이 포함된 것을 확인했다.

```text
PostgreSQL JDBC
Flyway core
Flyway PostgreSQL module
V1__create_base_schema.sql
```

생성된 운영 JAR에는 H2가 포함되지 않는다.

최종 체크포인트는 다음과 같다.

- PostgreSQL 18.4 local·benchmark 컨테이너가 독립적으로 실행된다.
- DB, 사용자, 포트 및 named volume이 분리됐다.
- PostgreSQL 인코딩과 locale이 동일하게 고정됐다.
- 활성 서비스는 빈 비밀번호로 DB를 초기화하지 않는다.
- 비활성 profile의 비밀번호가 선택한 profile 실행을 막지 않는다.
- 일반 애플리케이션 runtime에서 H2가 제거됐다.
- H2는 빠른 test runtime에서만 사용한다.
- Flyway가 PostgreSQL 스키마 생성 및 변경을 담당한다.
- Hibernate는 `ddl-auto=validate`로 정합성만 확인한다.
- V1이 네 테이블, 네 sequence, 제약조건 및 일반 인덱스를 생성한다.
- JPA `allocationSize=50`과 PostgreSQL `INCREMENT BY 50`이 일치한다.
- 빈 DB에서 V1 적용 후 local과 benchmark 애플리케이션이 부팅된다.
- 재시작 시 V1은 재실행되지 않고 checksum이 검증된다.
- PostgreSQL 데이터와 sequence 상태가 named volume에 유지된다.
- local과 benchmark의 데이터 및 Flyway history가 격리된다.
- 전체 회귀 테스트와 운영 JAR 생성이 성공한다.
- 검증용 컨테이너, volume 및 임시 파일은 검증 후 제거한다.

## 4. 1-3. 기존 기능과 benchmark 데이터 동등성 검증

### 4.1. 동등성 기준과 검증 범위

PostgreSQL 이전의 동등성은 데이터베이스 내부 구현이 물리적으로 같은지가 아니라,
같은 요청과 조건에서 관찰 가능한 애플리케이션 결과와 benchmark 데이터의 논리적
의미가 같은지로 판단한다.

기존 기능의 기준점은 다음과 같다.

```text
기존 기능 기준점
├── docs/post-search-evolution.md에 확정된 기능 요구사항
├── 기존 H2 기반 테스트가 검증하던 동작
└── 현재 Controller·Service·Repository의 기능 계약
```

PostgreSQL 이전으로 직접 영향을 받는 데이터베이스 경계를 검증 범위로 정했다.

- 빈 PostgreSQL에서 Flyway V1 적용과 Hibernate `validate`
- 네 엔티티의 sequence 기반 ID 생성과 관계 저장
- unique, check 및 foreign key 제약
- 게시글과 댓글의 soft delete 제외, 정렬 및 커서 조회
- 활성 게시글의 조회수 증가
- 기존 `LOWER(column) LIKE '%keyword%'` 검색 계약
- 검색 범위, 대소문자, LIKE 특수문자 escape 및 커서 응답

이미지 파일 저장, 파일 트랜잭션 생명주기, DTO 단독 검증, Mock 기반 Service 분기,
Security Filter 및 비밀번호 암호화는 DB 종류에 직접 의존하지 않으므로 기존 빠른 회귀
테스트가 담당한다.

benchmark 데이터는 다음 기준점을 사용한다.

```text
benchmark 데이터 기준점
├── BenchmarkPostDataFactory의 결정적 생성 규칙
├── 기존 benchmark 단위·통합 테스트
└── 기존 H2 측정에 사용한 설정과 marker 분포
```

같은 `seed`, 게시글 수 및 작성자 수에서 제목, 본문, 삭제 상태, 작성자 배치와 집계
분포가 같으면 논리적으로 동등한 데이터로 판단한다. 실제 ID 값, `created_at`과
`deleted_at`의 정확한 시각 및 sequence의 내부 현재 값은 실행과 DB 구현에 따라 달라질
수 있으므로 H2와 PostgreSQL 간 비교 대상에서 제외한다. ID의 유일성, 관계 참조와
활성·삭제 상태는 반드시 유지한다.

### 4.2. PostgreSQL 기존 기능 동등성 검증

빠른 H2 회귀 테스트와 PostgreSQL 통합 테스트의 실행 경로를 분리했다.

```bash
# 빠른 회귀 테스트
./gradlew test

# PostgreSQL 18.4 통합 테스트
./gradlew postgresIntegrationTest

# 전체 검증
./gradlew test postgresIntegrationTest
```

`postgresIntegrationTest` 전용 source set은 `src/postgresIntegrationTest`를 사용한다.
`spring-boot-testcontainers`와 `testcontainers-postgresql`을 해당 source set에만
연결하고, `postgres:18.4` 컨테이너를 Spring Boot `@ServiceConnection`으로 DataSource와
Flyway에 제공한다.

PostgreSQL 통합 테스트는 다음 조건으로 실행된다.

```text
Flyway: enabled
Hibernate ddl-auto: validate
PostgreSQL image: postgres:18.4
```

실제 PostgreSQL에서 다음 내용을 확인했다.

```text
스키마와 관계
├── Flyway V1 성공 이력
├── users_seq, posts_seq, comments_seq, post_likes_seq
├── 네 sequence의 increment_by = 50
└── User·Post·Comment·PostLike의 ID와 관계 저장

제약
├── email unique
├── nickname unique
├── post_id·user_id 좋아요 unique
├── 음수 게시글 카운터 check
└── 참조 중인 부모 행의 물리 삭제를 foreign key가 차단

기존 기능
├── soft delete 게시글·댓글 제외
├── 게시글 ID 내림차순과 댓글 ID 오름차순
├── 게시글 커서 페이지네이션
├── 활성 게시글만 조회수 증가
└── LOWER() LIKE 검색, 범위, escape 및 검색 커서 응답
```

PostgreSQL 기존 기능 동등성 테스트 9개가 모두 통과했다.

### 4.3. PostgreSQL benchmark generator와 데이터 동등성

기존 generator는 H2 JDBC URL에 `benchmark-data` 경로가 포함됐는지 확인했다.
PostgreSQL URL에는 파일 경로가 없으므로 실제 연결된 DB의 JDBC 식별 정보와 데이터
상태를 이용하도록 안전장치를 변경했다.

generator는 다음 조건을 모두 만족할 때만 실행된다.

```text
benchmark profile 활성화
    AND
database product = PostgreSQL
    AND
database name = community_benchmark
    AND
users = 0, posts = 0
```

따라서 H2, local PostgreSQL의 `community` DB 및 이미 데이터가 존재하는
`community_benchmark` DB에서는 실행을 차단한다.

기존 H2 generator 통합 테스트는 PostgreSQL 전용 통합 테스트로 이동했다. 데이터 생성
규칙 자체를 검증하는 `BenchmarkPostDataFactory` 단위 테스트와 generator 안전장치 단위
테스트는 빠른 테스트에 유지한다.

Testcontainers PostgreSQL 18.4에서 다음 설정으로 generator를 실행했다.

```text
post-count: 1000
author-count: 100
persistence-batch-size: 1000
seed: 20260802
```

기본 생성 결과는 다음과 같다.

| 항목 | 기대값 | 실제 결과 |
| --- | ---: | ---: |
| 사용자 | 100 | 100 |
| 게시글 | 1,000 | 1,000 |
| 활성 게시글 | 950 | 950 |
| 삭제 게시글 | 50 | 50 |
| 작성자별 게시글 | 10 | 모두 10 |
| 댓글 | 0 | 0 |
| 좋아요 | 0 | 0 |

저장된 게시글 1,000건을 ID 순서로 조회해 같은 sequence의 factory 출력과 제목, 본문,
삭제 상태 및 작성자 배치를 행 단위로 비교했으며 모두 일치했다.

검색 marker 분포는 다음과 같다.

| marker | 위치 | 기대값 | 실제 결과 |
| --- | --- | ---: | ---: |
| `COMMON` | 본문 | 95 | 95 |
| `MEDIUM` | 본문 | 9 | 9 |
| `RARE` | 본문 | 0 | 0 |
| `FIXED` | 본문 | 10 | 10 |
| `SCOPE` | 본문 | 9 | 9 |
| `SCOPE` | 제목 | 9 | 9 |
| `NEVER` | 본문 | 0 | 0 |

삭제 게시글의 marker 포함 건수는 0건이다. 본문 길이 분포도 기존 규칙과 일치했다.

| 본문 길이 | 기대값 | 실제 결과 |
| --- | ---: | ---: |
| 300~799자 | 600 | 600 |
| 800~1,999자 | 300 | 300 |
| 2,000~7,999자 | 90 | 90 |
| 8,000~15,999자 | 0 | 0 |
| 16,000~32,000자 | 10 | 10 |

모든 게시글의 이미지가 `NULL`이고 좋아요, 댓글 및 조회수 카운터가 0이며, 모든
사용자의 프로필 이미지가 `NULL`인 것도 확인했다.

### 4.4. 반복 실행과 최종 역검증

Gradle의 이전 성공 결과를 재사용하지 않도록 다음 전체 검증을 두 번 실행했다.

```bash
./gradlew test postgresIntegrationTest --rerun-tasks
```

| 실행 | Gradle task | 결과 |
| --- | ---: | --- |
| 1차 | 8개 실제 실행 | `BUILD SUCCESSFUL` |
| 2차 | 8개 실제 실행 | `BUILD SUCCESSFUL` |

각 실행에서 PostgreSQL 기능 동등성 테스트 9개와 benchmark 데이터 동등성 테스트
4개가 실패, 오류 및 skip 없이 통과했다. 1차 실행 종료 후 `postgres:18.4` 실행
컨테이너가 0개인 상태에서 2차 실행이 새 `community_benchmark` 컨테이너를 생성했으므로
첫 번째 DB 상태를 재사용하지 않았다. 2차 실행 종료 후에도 컨테이너가 남지 않았다.

최종 검증 결과는 다음과 같다.

```text
빠른 H2 회귀 테스트
└── BUILD SUCCESSFUL

PostgreSQL 통합 테스트
├── 기존 기능 동등성: 9개
├── benchmark 데이터 동등성: 4개
├── skipped: 0
├── failures: 0
└── errors: 0

전체 강제 재실행 2회
└── 모두 BUILD SUCCESSFUL

git diff --check
└── 통과
```

1-3 완료 체크포인트는 다음과 같다.

- 기능 및 benchmark 데이터의 동등성 기준과 비교 제외값을 구분했다.
- 빠른 H2 회귀 테스트와 PostgreSQL 통합 테스트 실행 경로를 분리했다.
- Flyway V1, 엔티티 sequence·관계 및 주요 DB 제약을 PostgreSQL에서 검증했다.
- soft delete, 정렬, 커서, 조회수 및 기존 LIKE 검색 계약을 PostgreSQL에서 검증했다.
- generator를 PostgreSQL `community_benchmark` 빈 DB에서만 실행하도록 제한했다.
- 1천 건의 논리 행과 작성자, 삭제, marker 및 본문 길이 분포가 기존 규칙과 일치한다.
- 캐시 없이 전체 검증을 두 번 실행해 깨끗한 DB에서 결과가 재현됨을 확인했다.
- Testcontainers가 테스트 후 PostgreSQL 컨테이너를 정리함을 확인했다.

## 5. 1-4. 재현성과 전체 회귀 검증 및 LIKE 기준선 측정 준비

### 5.1. 10만 건 canonical benchmark 데이터 생성·검증

검색 방식 비교의 원본으로 사용할 데이터를 `postgres-benchmark`에 생성했다.

```text
PostgreSQL image: postgres:18.4
database: community_benchmark
user: community_benchmark
host port: 5433
volume: community-postgres-benchmark-data
```

generator 입력은 다음 값으로 고정했다.

```text
post-count: 100000
author-count: 100
persistence-batch-size: 1000
seed: 20260802
```

Flyway V1과 Hibernate `validate`가 통과한 빈 benchmark DB에서 generator를 실행했다.
사용자 100명과 게시글 10만 건이 생성됐으며 generator가 기록한 약 10.9초는 데이터 생성
완료 여부를 확인하기 위한 값일 뿐 검색 성능 결과로 사용하지 않는다.

기본 데이터 상태의 검증 결과는 다음과 같다.

| 항목 | 기대값 | 실제값 |
| --- | ---: | ---: |
| 사용자 | 100 | 100 |
| 게시글 | 100,000 | 100,000 |
| 활성 게시글 | 95,000 | 95,000 |
| 삭제 게시글 | 5,000 | 5,000 |
| 작성자별 게시글 | 1,000 | 최소·최대 모두 1,000 |
| 댓글 | 0 | 0 |
| 좋아요 | 0 | 0 |

검색 marker는 삭제되지 않은 게시글에만 배치된다.

| marker | 위치 | 기대값 | 실제값 |
| --- | --- | ---: | ---: |
| `qzcommona91x` | 본문 | 9,500 | 9,500 |
| `rxmediumb82y` | 본문 | 950 | 950 |
| `tvrarec73z` | 본문 | 95 | 95 |
| `wxfixedd64k` | 본문 | 10 | 10 |
| `ypscopee55m` | 제목 | 950 | 950 |
| `ypscopee55m` | 본문 | 950 | 950 |
| `zvneverf46n` | 제목·본문 | 0 | 0 |

`ypscopee55m`이 제목과 본문에 들어간 게시글 ID 집합은 동일했으며 삭제 게시글에 포함된
marker는 0건이었다.

본문 길이 분포도 generator 규칙과 일치했다.

| 본문 길이 | 기대값 | 실제값 |
| --- | ---: | ---: |
| 300~799자 | 60,000 | 60,000 |
| 800~1,999자 | 30,000 | 30,000 |
| 2,000~7,999자 | 9,000 | 9,000 |
| 8,000~15,999자 | 0 | 0 |
| 16,000~32,000자 | 1,000 | 1,000 |

다음 무결성 조건도 함께 확인했다.

```text
필수 컬럼 NULL: 0건
고아 posts.user_id: 0건
content_image가 설정된 게시글: 0건
like_count·comment_count·view_count가 0이 아닌 게시글: 0건
pg_trgm extension: 없음
tsvector 컬럼: 없음
```

이 검증을 다시 실행할 수 있도록
`benchmark/postgresql/verify-canonical-dataset.sql`을 추가했다. 검증 SQL은 기대값이 하나라도
다르면 예외를 발생시키며, 원본 DB와 dump 복원본에서 모두 통과했다. 검증을 마친 현재
`community_benchmark`를 canonical dataset 원본으로 확정했다.

### 5.2. dump·checksum·restore 기반 재현성 검증

canonical 원본을 검색 방식마다 같은 상태로 복원하기 위해 PostgreSQL custom-format의
data-only dump를 생성했다.

```text
file: benchmark-data/postgresql/canonical/community-benchmark-100k.dump
format: PostgreSQL custom data-only archive
size: 51,775,875 bytes
SHA-256: e2dbcf795e0b124ac93f210541d49e9e8da93064cc804a779a155e6325c374c6
```

archive에는 다음 항목이 포함된다.

```text
포함
├── users 데이터
├── posts 데이터
├── comments 데이터
├── post_likes 데이터
└── 네 sequence의 상태

제외
├── 테이블·제약·인덱스와 같은 schema DDL
├── owner와 privileges
└── flyway_schema_history 데이터
```

schema와 Flyway 이력을 제외했으므로 복원 대상 DB에는 현재 애플리케이션의 Flyway V1을
먼저 적용해야 한다. 이를 통해 오래된 dump가 애플리케이션의 확정된 schema를 덮어쓰지
않고, 현재 migration과 데이터가 서로 호환되는지도 함께 확인한다.

checksum 파일과 생성 provenance는 다음 파일로 분리했다.

```text
community-benchmark-100k.dump.sha256
└── dump 파일 바이트가 변경되지 않았는지 확인

community-benchmark-100k.manifest.txt
└── PostgreSQL image, Git HEAD, generator 설정과 파일 hash,
    데이터 fingerprint, sequence 및 최초 restore 결과 기록
```

별도 임시 DB에 다음 순서로 복원 검증을 수행했다.

```text
빈 community_benchmark_restore_verify 생성
    ↓
애플리케이션으로 Flyway V1 적용
    ↓
Hibernate validate 성공
    ↓
checksum 재확인
    ↓
pg_restore --exit-on-error 실행
    ↓
전체 canonical 분포 검증
    ↓
원본·복원본 전체 컬럼 fingerprint 비교
    ↓
sequence 상태 비교
    ↓
ANALYZE 실행
```

역검증에서 사용자와 게시글의 모든 컬럼을 ID 순서대로 다시 hash한 결과는 다음과 같다.

| 대상 | 원본 | 복원본 |
| --- | --- | --- |
| users | `157df43f62ff3ef7274e75a4c58e7df2` | `157df43f62ff3ef7274e75a4c58e7df2` |
| posts | `ae5472e5c84907730a6a4a46ce50f811` | `ae5472e5c84907730a6a4a46ce50f811` |

fingerprint 계산 방식이 최초 manifest를 만들 때 사용한 방식과 달라 hash 문자열 자체는
manifest의 값과 다르다. 같은 계산식을 적용한 원본과 복원본이 일치하는지가 동등성 판단
기준이다. 두 방식 모두 원본과 복원본이 일치했다.

sequence도 원본과 복원본에서 동일했다.

| sequence | last value | increment | 다음 값의 기준 |
| --- | ---: | ---: | --- |
| `users_seq` | 101 | 50 | 최대 `user_id`보다 큰 범위 |
| `posts_seq` | 100,001 | 50 | 최대 `post_id`보다 큰 범위 |
| `comments_seq` | 호출 전 | 50 | 데이터 없음 |
| `post_likes_seq` | 호출 전 | 50 | 데이터 없음 |

복원본의 Flyway 이력은 V1 성공 1건뿐이며 dump에서 유입된 이력이 아니다. 복원 후
`ANALYZE`도 완료했다. 역검증에 사용한 임시 DB는 삭제했고 canonical 원본과 dump는
보존했다.

### 5.3. 전체 회귀 검증과 실행 환경 격리

일반 개발용 DB와 benchmark DB를 동시에 확인해 서로 다른 database, port 및 volume을
사용하는지 검증했다.

```text
postgres-local
├── database: community
├── port: 127.0.0.1:5432
├── volume: community-postgres-local-data
└── 데이터: 사용자 0, 게시글 0

postgres-benchmark
├── database: community_benchmark
├── port: 127.0.0.1:5433
├── volume: community-postgres-benchmark-data
└── 데이터: 사용자 100, 게시글 100,000
```

두 컨테이너의 동시 시작, 중지 및 재시작 후에도 local은 빈 상태였고 benchmark의 건수와
게시글 fingerprint는 유지됐다. 공식 측정 준비 스크립트는 local 컨테이너를 중지한 뒤
benchmark 컨테이너만 시작하므로 두 PostgreSQL 프로세스의 자원 경쟁도 방지한다.

전체 검증은 이전 Gradle 결과를 재사용하지 않도록 강제 재실행했다.

```bash
./gradlew test postgresIntegrationTest bootJar --rerun-tasks
```

| 실행 경로 | 테스트 수 | skipped | failures | errors |
| --- | ---: | ---: | ---: | ---: |
| 빠른 H2 회귀 테스트 | 124 | 0 | 0 | 0 |
| PostgreSQL 통합 테스트 | 13 | 0 | 0 | 0 |

Gradle task 10개가 모두 실제 실행됐고 `BUILD SUCCESSFUL`로 종료됐다. 테스트 전후
canonical 게시글 fingerprint는 `ae5472e5c84907730a6a4a46ce50f811`로 동일했다.
Testcontainers PostgreSQL과 Ryuk 컨테이너도 테스트 프로세스 종료 후 정리됐다.

생성된 실행 JAR도 확인했다.

```text
Flyway V1 migration: 포함
PostgreSQL JDBC 42.7.10: 포함
Flyway PostgreSQL 11.14.1: 포함
H2: 포함하지 않음
JAR SHA-256: 5db7f0045bc2da093291978e65b17f729813df6af965f214fc224e1966fbeccb
```

H2는 테스트 runtime에만 존재하므로 benchmark 애플리케이션 실행 결과에 영향을 주지
않는다.

### 5.4. PostgreSQL LIKE 기준선 측정 조건 확정·준비

현재 JPA의 `LOWER(column) LIKE '%keyword%'` 검색을 변경하지 않고 PostgreSQL 기준선을
측정하기 위한 조건을 고정했다.

애플리케이션 실행 조건은 다음과 같다.

```text
Java: Eclipse Temurin 21.0.11
Spring profile: benchmark
server port: 18084
generator: disabled
JVM heap: -Xms1g -Xmx1g
Hikari maximum-pool-size: 10
Hikari minimum-idle: 10
Hikari connection-timeout: 30000ms
```

PostgreSQL readiness 과정에서는 canonical dump checksum을 먼저 확인하고 `ANALYZE`를
실행한다. 측정 시 기록할 현재 주요 설정은 다음과 같다.

```text
PostgreSQL: 18.4 (Debian 18.4-1.pgdg13+1)
shared_buffers: 128MB
work_mem: 4MB
maintenance_work_mem: 64MB
effective_cache_size: 4GB
max_connections: 100
random_page_cost: 4
jit: on
```

k6도 tag가 아닌 image digest로 고정했다.

```text
k6: 2.1.0
platform: linux/arm64
image: grafana/k6@sha256:e7eeddf1ce2361df6920d925297f487c0ba549c44be242c6a9c22f28d9b08efa
```

공통 요청 조건은 다음과 같다.

```http
GET /posts?keyword=qzcommona91x&scope=all&size=10
```

측정 순서와 반복 조건을 고정했다.

```text
smoke
└── 1 VU, 1 iteration

warm-up
└── 1 VU, 30초

단일 사용자 공식 측정
└── 1 VU, 60초, 3회

목표 부하 공식 측정
└── constant-arrival-rate 50 RPS, 60초, pre-allocated VUs 50, 3회
```

단일 사용자 시나리오는 요청 적체가 거의 없는 기본 지연 시간을 확인하고,
constant-arrival-rate 시나리오는 이전 요청의 완료와 관계없이 초당 50건을 시작하려고
시도한다. 후자에서는 p50·p95·실제 처리량뿐 아니라 시작하지 못한 요청을 나타내는
`dropped_iterations`도 함께 비교한다.

측정 준비 파일은 다음과 같다.

```text
benchmark/postgresql/
├── verify-canonical-dataset.sql
├── verify-like-baseline-ready.sql
├── prepare-like-baseline.sh
├── start-like-baseline-app.sh
├── explain-like-baseline.sql
└── run-like-baseline-suite.sh
```

각 파일의 역할은 다음과 같다.

| 파일 | 역할 |
| --- | --- |
| `verify-canonical-dataset.sql` | canonical 전체 건수·분포·무결성 검증 |
| `verify-like-baseline-ready.sql` | DB·Flyway·통계·검색 확장 부재 확인 |
| `prepare-like-baseline.sh` | checksum 확인, local 중지, benchmark 시작, canonical 전체 검증, `ANALYZE`, readiness 실행 |
| `start-like-baseline-app.sh` | 고정 Java·JVM·profile로 애플리케이션 실행 |
| `explain-like-baseline.sql` | 대표 LIKE 쿼리의 `ANALYZE, BUFFERS, WAL, SETTINGS` 실행 계획 수집 |
| `run-like-baseline-suite.sh` | smoke, warm-up, 공식 반복 측정 및 결과 파일 저장 |

부하 테스트 결과는 실행 시각별 디렉터리에 반복별 `.log`와 `.json`으로 저장하도록 했다.

```text
benchmark-data/postgresql/like-baseline/results/{timestamp}/
├── smoke.log / smoke.json
├── warmup.log / warmup.json
├── single-user-1..3.log / .json
└── arrival-rate-1..3.log / .json
```

현재 JAR로 애플리케이션을 부팅해 Java 21, benchmark profile, PostgreSQL 18.4 연결,
Flyway V1 및 Hibernate validate를 다시 확인했다. Actuator health는 HTTP 200이었고 k6
smoke의 다음 조건이 모두 통과했다.

```text
HTTP 200
JSON 응답
게시글 10건
has_next=true와 next_cursor 존재
checks: 4/4
HTTP failures: 0
```

smoke의 단발 지연 시간은 JVM, DispatcherServlet 및 캐시 초기화의 영향을 받으므로 공식
성능 결과로 사용하지 않는다. `EXPLAIN ANALYZE`, 1 VU 3회 및 50 RPS 3회는 아직
실행하지 않았다.

### 5.5. 1-4 전체 역검증 결과

1-4-A부터 D까지의 체크포인트를 실제 DB, dump, 테스트 결과 및 실행 파일에서 다시
확인했다.

| 구분 | 역검증 체크포인트 | 결과 |
| --- | --- | --- |
| A | 고정 generator 설정으로 10만 건 canonical 데이터가 생성됐는가 | 통과 |
| A | 건수·작성자·삭제·marker·본문 길이 분포가 기대값과 일치하는가 | 통과 |
| A | 기본값·필수값·관계 무결성이 유지되는가 | 통과 |
| B | dump checksum이 기록값과 일치하는가 | 통과 |
| B | 빈 Flyway V1 DB에 data-only dump가 오류 없이 복원되는가 | 통과 |
| B | 원본·복원본 전체 컬럼 fingerprint와 sequence가 같은가 | 통과 |
| B | 복원 후 `ANALYZE`와 전체 canonical 검증이 성공하는가 | 통과 |
| C | local·benchmark DB, port 및 volume이 분리됐는가 | 통과 |
| C | 전체 137개 테스트와 boot JAR 생성이 성공하는가 | 통과 |
| C | 테스트·재시작 전후 canonical 데이터가 변하지 않는가 | 통과 |
| C | Testcontainers가 종료 후 정리되는가 | 통과 |
| D | Java·JVM·Hikari·PostgreSQL·k6 조건이 고정됐는가 | 통과 |
| D | LIKE 외 검색 확장 구조가 없는가 | 통과 |
| D | readiness, 부팅, health 및 smoke가 성공하는가 | 통과 |
| D | 실행 계획과 반복 부하 테스트 및 결과 저장 경로가 준비됐는가 | 통과 |
| D | 공식 LIKE 성능 수치를 아직 기준선 결과로 기록하지 않았는가 | 통과 |

따라서 PostgreSQL 이전, canonical 데이터 재현성, 전체 회귀 및 LIKE 기준선 측정 준비를
포함한 1단계를 완료했다.

## 6. 현재 상태와 다음 작업

### 6.1. PostgreSQL 이전 완료 상태

```text
1. PostgreSQL 이전 기반
├── 1-1. 이전 정책과 환경 설계
├── 1-2. Compose·Flyway V1·sequence 기반 구축
├── 1-3. 기존 기능과 benchmark 데이터 동등성 검증
└── 1-4. canonical dump·restore·전체 회귀·LIKE 측정 준비
```

현재 검색 구현은 기존 JPA `LOWER(column) LIKE '%keyword%'`를 유지한다. PostgreSQL
환경과 재현 가능한 데이터셋은 완성됐지만 PostgreSQL LIKE의 공식 성능 기준선은 아직
측정하지 않았다. `pg_trgm`과 Full Text Search도 적용하지 않았다.

### 6.2. 다음 단계의 경계

다음 단계는 `2. PostgreSQL LIKE 기준선 측정`이다.

1. 공식 측정 직전 Git 상태와 파일 hash를 measurement manifest에 확정한다.
2. canonical checksum, readiness 및 `ANALYZE`를 다시 확인한다.
3. 대표 LIKE 쿼리의 `EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS)`를 수집한다.
4. smoke와 30초 warm-up 후 1 VU 60초 측정을 3회 실행한다.
5. 50 RPS 60초 측정을 3회 실행한다.
6. 반복별 p50·p95·처리량·실패·`dropped_iterations`를 정리하고 대표값을 확정한다.

LIKE 기준선을 확정한 이후에만 같은 canonical dump와 실행 조건으로 `pg_trgm` 및 Full
Text Search 실험을 진행한다.

## 7. 2. PostgreSQL LIKE 기준선 측정

PostgreSQL 이전만 완료한 V1 상태에서 기존 JPA
`LOWER(column) LIKE '%keyword%'` 검색의 실행 계획과 애플리케이션 성능을 공식
기준선으로 측정한다. 이 단계에서는 `pg_trgm`, `tsvector` 또는 검색 후보 인덱스를
추가하지 않는다.

측정은 다음 세 단계로 진행한다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 2-1 | 측정 상태 확정과 LIKE 실행 계획 분석 | 완료 |
| 2-2 | 단일 사용자·50 RPS 공식 성능 측정 | 완료 |
| 2-3 | 결과 분석과 PostgreSQL LIKE 기준선 확정 | 진행 전 |

### 7.1. 2-1. 측정 상태 확정과 LIKE 실행 계획 분석

#### 2-1-A. 공식 측정 대상 상태 확정

1-4-D에서는 측정 절차와 도구가 실행 가능한지 검증했다. 2-1-A에서는 그 도구로 실제
공식 측정에 사용할 소스, JAR, 데이터, PostgreSQL 및 k6 상태를 하나의 snapshot으로
고정했다.

##### 소스 snapshot

공식 측정 대상의 Git 기준은 다음과 같다.

```text
branch: feature/postgresql-search
Git HEAD: 473c646a317936583dfa9dd179c36e3558bbd792
Git tree: c336993d6afcc84f85496befab4c8ebe24aaa418
```

현재 작업 트리에 미커밋 파일이 있지만 역할을 구분했다.

```text
애플리케이션 runtime 소스
└── Git HEAD와 차이 없음

측정 절차 변경
├── benchmark/postgresql/prepare-like-baseline.sh
└── benchmark/postgresql/verify-canonical-dataset.sql

측정 결과에 영향을 주지 않는 변경
├── docs/postgresql-search-evolution.md
├── .gitignore
└── .DS_Store
```

측정 절차 변경 두 개는 measurement manifest에 개별 SHA-256을 기록했다. 따라서 공식
측정 대상은 `Git HEAD + 측정 절차 파일 hash`로 식별한다. 문서와 결과 파일 외의 대상이
변경되면 snapshot은 무효이며 2-1-A를 다시 수행한다.

##### 빌드와 애플리케이션 artifact

이전 Gradle 결과를 재사용하지 않고 다음 명령을 실행했다.

```bash
./gradlew test postgresIntegrationTest bootJar --rerun-tasks
```

```text
Gradle tasks: 10개 실제 실행
빠른 테스트: 124개, 실패·오류·skip 0
PostgreSQL 통합 테스트: 13개, 실패·오류·skip 0
결과: BUILD SUCCESSFUL
```

공식 측정에 사용할 JAR는 다음 하나로 고정했다.

```text
file: build/libs/community-0.0.1-SNAPSHOT.jar
SHA-256: 5db7f0045bc2da093291978e65b17f729813df6af965f214fc224e1966fbeccb
Java runtime: Eclipse Temurin 21.0.11
JVM heap: -Xms1g -Xmx1g
Spring profile: benchmark
server port: 18084
generator: disabled
Hikari pool: minimum 10, maximum 10
```

Gradle launcher는 Java 26을 사용하지만 프로젝트 toolchain과 공식 애플리케이션 runtime은
Java 21이다. 성능 측정 대상 JVM은 `start-like-baseline-app.sh`가 선택하는 Java
21.0.11이다.

##### 호스트와 Docker 조건

성능 수치가 생성되는 실행 환경도 snapshot에 포함했다.

```text
host model: Mac14,9
host memory: 16 GiB
host logical CPUs: 10
host OS: macOS 15.7.2, arm64

Docker Desktop server: 29.6.2
Docker CPUs: 10
Docker memory: 8,321,515,520 bytes
Docker architecture: aarch64
```

공식 비교에서 LIKE, `pg_trgm` 및 FTS는 같은 호스트와 Docker 자원 조건을 사용한다.

##### PostgreSQL과 canonical 데이터

`prepare-like-baseline.sh`를 실행해 checksum, canonical 전체 분포, `ANALYZE` 및
readiness를 다시 검증했다.

```text
PostgreSQL image: postgres:18.4
image ID: sha256:3a82e1f56c8f0f5616a11103ac3d47e632c3938698946a7ad26da0df1334744a
platform: linux/arm64
database: community_benchmark
host port: 127.0.0.1:5433
volume: community-postgres-benchmark-data
health: healthy
```

```text
users: 100
posts: 100,000
active posts: 95,000
deleted posts: 5,000
COMMON matches: 9,500
users fingerprint: 157df43f62ff3ef7274e75a4c58e7df2
posts fingerprint: ae5472e5c84907730a6a4a46ce50f811
canonical verification: 통과
dump checksum: 통과
ANALYZE: 완료
```

확장은 `plpgsql`만 존재했다. `pg_trgm`, `tsvector` 및 검색 함수 인덱스는 없다.
`posts`의 인덱스는 V1에 확정된 다음 세 개뿐이다.

```text
pk_posts
idx_posts_user_id
idx_posts_active_cursor
```

따라서 `LOWER(title)` 또는 `LOWER(content)`의 문자열 검색을 직접 지원하는 인덱스가 없는
PostgreSQL LIKE 기준 상태다.

##### k6와 요청 조건

```text
k6: 2.1.0, linux/arm64
image: grafana/k6@sha256:e7eeddf1ce2361df6920d925297f487c0ba549c44be242c6a9c22f28d9b08efa
request: GET /posts?keyword=qzcommona91x&scope=all&size=10
```

현재 JAR를 고정 조건으로 부팅한 뒤 Actuator health와 k6 smoke를 다시 실행했다.

```text
Actuator health: HTTP 200
smoke checks: 4/4
HTTP failures: 0/1
결과: 게시글 10건, has_next=true, next_cursor 존재
```

smoke의 지연 시간은 공식 성능 결과로 사용하지 않는다.

##### Measurement manifest

최종 snapshot은 Git에서 추적하지 않는 다음 파일에 기록했다.

```text
benchmark-data/postgresql/like-baseline/measurement-manifest.txt
SHA-256: d4a52b5b7832fdb0094cc4ffb224d4f4fae4646b61930d05dd158ac39d242a4e
```

manifest에는 Git, JAR, 주요 소스와 측정 파일 hash, 호스트, Docker, PostgreSQL 설정,
canonical fingerprint, k6 및 부하 조건이 포함된다. 별도의 `.sha256` 파일로 manifest
자체의 변경 여부도 확인한다.

2-1-A 이후 허용되는 변경은 결과 파일과 문서뿐이다. 다음 항목이 바뀌면 2-1-B 또는
2-2 결과와 같은 기준선으로 취급하지 않고 2-1-A부터 다시 검증한다.

```text
Git HEAD
애플리케이션 JAR hash
canonical fingerprint
PostgreSQL image 또는 설정
k6 image 또는 script
대표 실행 계획 SQL
검색어·범위·응답 크기
warm-up·반복·부하 조건
```

##### 2-1-A 완료 체크포인트

- 애플리케이션 runtime 소스가 Git HEAD와 일치한다.
- 측정 절차의 미커밋 변경을 개별 hash로 고정했다.
- 전체 137개 테스트와 boot JAR 재생성이 성공했다.
- 공식 JAR와 Java·JVM·Spring·Hikari 조건을 확정했다.
- 호스트와 Docker 자원 조건을 기록했다.
- PostgreSQL image, database, port 및 volume을 확정했다.
- canonical dump checksum, 전체 분포 및 fingerprint 검증을 통과했다.
- `ANALYZE`와 PostgreSQL 주요 설정을 확인했다.
- LIKE 외 검색 확장과 검색 함수 인덱스가 없음을 확인했다.
- k6 image와 세 측정 script의 hash를 확정했다.
- health와 smoke의 응답 계약을 확인했다.
- measurement manifest와 checksum을 생성했다.
- 공식 `EXPLAIN ANALYZE`와 부하 측정은 아직 실행하지 않았다.

따라서 공식 LIKE 실행 계획을 수집할 측정 대상 상태를 확정했다.

#### 2-1-B. 대표 LIKE 쿼리 실행 계획 수집

PostgreSQL이 기존 `LOWER(column) LIKE '%keyword%'` 검색을 처리하는 방식을 확인하기
위해 첫 페이지 검색과 의미상 동일한 대표 SQL의 실행 계획을 수집했다. 직접 실행한 SQL은
Hibernate가 생성한 SQL 문자열 자체가 아니라 조인, 검색 조건, 정렬 및 11건 제한을 같은
의미로 표현한 SQL이다.

```sql
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS)
SELECT p.*, u.*
FROM posts p
JOIN users u ON u.user_id = p.user_id
WHERE p.deleted_at IS NULL
  AND (
      LOWER(p.title) LIKE '%qzcommona91x%' ESCAPE '\'
      OR LOWER(p.content) LIKE '%qzcommona91x%' ESCAPE '\'
  )
ORDER BY p.post_id DESC
FETCH FIRST 11 ROWS ONLY;
```

공식 snapshot과 canonical readiness를 다시 확인한 뒤 같은 SQL을 warm-cache 조건에서
3회 순차 실행했다. 세 실행은 모두 같은 plan shape를 사용했다.

```text
Limit
└── Nested Loop
    ├── Index Scan Backward using pk_posts
    │   └── deleted_at과 LIKE 조건을 Filter로 평가
    │
    └── Materialize
        └── Seq Scan on users
```

| 실행 | Planning Time | Execution Time | 상위 실행 buffer | posts filter 제거 |
| --- | ---: | ---: | --- | ---: |
| 1회 | 1.139ms | 2.958ms | hit 81, read 1 | 96 |
| 2회 | 0.753ms | 1.935ms | hit 82 | 96 |
| 3회 | 0.393ms | 1.794ms | hit 82 | 96 |
| 중앙값 | 0.753ms | 1.935ms | - | 96 |

Execution Time은 PostgreSQL 내부 SQL 실행 시간이다. HTTP, Spring MVC, Hibernate,
JDBC, DTO 변환 및 JSON 직렬화 비용이 없으므로 API 응답 시간이나 부하 성능 결과로
사용하지 않는다.

실행 전후 데이터는 동일했다.

```text
users: 100
posts: 100,000
active posts: 95,000
deleted posts: 5,000
COMMON matches: 9,500
posts fingerprint: ae5472e5c84907730a6a4a46ce50f811
```

원본은 다음 디렉터리에 보존하고 파일별 SHA-256을 검증했다.

```text
benchmark-data/postgresql/like-baseline/explain/
└── postgresql-like-baseline-20260804-155324-kst/
    ├── before-state.log
    ├── explain-run-1.log
    ├── explain-run-2.log
    ├── explain-run-3.log
    ├── after-state.log
    └── SHA256SUMS
```

#### 2-1-C. 실행 계획 분석과 측정 전 최종 판정

##### 게시글 접근과 정렬

PostgreSQL은 `posts` 전체 순차 스캔을 선택하지 않았다. 기본키 B-tree인 `pk_posts`를
역방향으로 스캔해 `ORDER BY post_id DESC` 순서를 바로 만들었다.

```text
Index Scan Backward using pk_posts
    ↓
최신 post_id부터 조회
    ↓
deleted_at과 LIKE Filter 평가
    ↓
11건을 찾으면 Limit에서 종료
```

인덱스에서 이미 필요한 정렬 순서로 행을 읽으므로 별도의 `Sort` 노드는 없다.
`idx_posts_active_cursor`도 최신 활성 게시글 조회를 지원할 수 있지만 이번 계획에서는
선택되지 않았고 `pk_posts`에서 삭제 여부까지 Filter로 확인했다.

##### LIKE 조건과 조기 종료

LIKE 조건은 `Index Cond`가 아니라 다음 `Filter`로 나타났다.

```text
lower(title) LIKE '%qzcommona91x%'
OR lower(content) LIKE '%qzcommona91x%'
```

이는 `pk_posts`가 LIKE 일치 위치를 찾아 주는 것이 아니라, 기본키 순서로 가져온 각 행에
대해 PostgreSQL이 제목과 본문을 소문자로 변환하고 문자열 포함 여부를 검사한다는
의미다.

이번 실행에서는 반환 11건 외에 96건이 Filter에서 제거됐다. 따라서 최신순으로 약
107건의 게시글 조건을 평가한 시점에 필요한 11건을 찾고 종료했다.

```text
평가한 게시글: 약 107건
├── LIKE 일치 후 반환: 11건
└── 삭제 또는 LIKE 불일치로 제거: 96건
```

전체 10만 건을 평가하지 않은 이유는 COMMON marker가 활성 게시글 10개마다 하나씩
분포하고, 첫 페이지에서 11건만 필요하기 때문이다. 이는 일반적인 선행 와일드카드 LIKE가
인덱스로 검색어를 찾았다는 의미가 아니다. `LIMIT`와 현재 데이터 분포가 최신 영역에서
빠른 조기 종료를 가능하게 한 것이다.

따라서 다음 조건에서는 훨씬 더 많은 게시글을 확인할 수 있다.

```text
일치 빈도가 낮은 검색어
일치 결과가 없는 검색어
깊은 cursor 페이지
최신 게시글 구간에 결과가 적은 데이터 분포
```

2-2의 공식 기준선은 계획대로 `COMMON + all + 첫 페이지` 조건을 측정한다. 결과를 모든
검색어와 페이지 위치의 일반 성능으로 확대 해석하지 않는다. 이후 `pg_trgm`과 FTS도
동일한 조건을 사용해 비교한다.

##### 예상 행 수와 실제 분포

`posts` Index Scan에서 PostgreSQL의 예상 일치 행은 19건이었다. Canonical 데이터의
실제 COMMON 일치 건수는 9,500건이므로 약 500배의 과소 추정이다.

```text
planner 예상: 19건
canonical 실제 전체 일치: 9,500건
차이: 약 500배
```

실행 계획의 actual rows는 `LIMIT` 때문에 11건에서 중단됐으므로 9,500건을 직접 세어
표시하지 않는다. 실제 전체 일치 수는 별도 canonical 검증 쿼리로 확인했다.

PostgreSQL은 일반 컬럼 통계만으로 `LOWER(column) LIKE '%keyword%'` 안의 임의 부분
문자열 분포를 정확히 알기 어렵다. 이 선택도 추정 오차 때문에 `Limit`의 예상 비용은
`11376.40`까지 표시됐지만 실제로는 높은 marker 빈도 덕분에 적은 행을 확인하고
종료했다. 실행 계획의 cost는 옵티마이저가 계획끼리 비교하기 위한 상대 단위이며 ms가
아니다.

##### 작성자 조인

게시글과 작성자는 `Nested Loop`로 조인했다. 사용자 100명을 한 번 순차 스캔해 약
30kB의 메모리에 `Materialize`하고, 선택된 게시글마다 작성자 ID가 일치할 때까지 이 작은
결과를 재사용했다.

```text
Seq Scan on users
└── 100명

Materialize
└── 최대 30kB

Rows Removed by Join Filter
└── 603건
```

현재 사용자가 100명으로 작아 조인 쪽 buffer는 2개였으며, 이번 대표 계획의 주된 검색
비용은 게시글 행마다 LIKE Filter를 평가하는 부분이다.

##### Buffer, WAL 및 반복 차이

첫 실행의 상위 실행 buffer는 `shared hit=81 read=1`, 이후 두 실행은 `shared hit=82`였다.
첫 실행에서 읽은 1개 블록이 다음 실행부터 shared buffer에서 재사용됐다. 준비 단계의
canonical 검증과 `ANALYZE`가 이미 데이터를 읽었으므로 첫 실행도 완전한 cold-cache
조건은 아니다.

읽기 전용 SELECT이므로 보고할 WAL 활동이 없었고, 세션에서 실행 계획 관련 설정을
별도로 변경하지 않아 `SETTINGS` 항목도 출력되지 않았다.

##### 2-2 진행 판정

2-1의 최종 체크포인트는 모두 통과했다.

- 공식 소스, JAR, DB, canonical 데이터 및 k6 조건을 snapshot으로 고정했다.
- LIKE 외 검색 확장과 검색 함수 인덱스가 없는 상태를 확인했다.
- 대표 SQL의 실행 계획을 같은 조건으로 3회 수집했다.
- 세 실행에서 동일한 plan shape를 확인했다.
- 원본 계획과 실행 전후 상태 및 SHA-256을 보존했다.
- 실행 전후 canonical 건수와 fingerprint가 동일했다.
- COMMON 첫 페이지와 warm-cache라는 결과 해석 범위를 명시했다.
- DB 내부 실행 시간과 API 응답 시간을 구분했다.
- 애플리케이션 부하 측정을 막는 이상 상태가 없다.

따라서 2-2의 단일 사용자 및 50 RPS 공식 성능 측정을 시작할 수 있다고 판정했다.

### 7.2. 2-2. 단일 사용자·50 RPS 공식 성능 측정

2-1에서 분석한 PostgreSQL LIKE 상태를 애플리케이션 전체 경로에서 측정했다. 공식
요청에는 HTTP, Spring MVC, Hibernate, JDBC, PostgreSQL 실행, DTO 변환 및 JSON
직렬화 비용이 모두 포함된다.

```text
GET /posts?keyword=qzcommona91x&scope=all&size=10
```

각 요청에서는 성능 지표와 함께 다음 응답 계약을 검증했다.

```text
HTTP status: 200
응답 형식: JSON
반환 게시글: 10건
has_next: true
next_cursor: 존재
```

#### 2-2-A. 단일 사용자 공식 성능 측정

##### 측정 snapshot 재확정

2-1 이후 Git HEAD가 변경되어 부하 실행 전에 변경 범위를 확인했다.

```text
2-1 snapshot HEAD: 473c646a317936583dfa9dd179c36e3558bbd792
2-2 측정 HEAD: e7dcdb785a578ada61f1077fc2cca90b0e08d04f
2-2 측정 tree: dd8fc8d0b95e5524442d74fba9ca69ff0eb821f2
```

변경은 문서, Git 제외 설정 및 canonical 검증 절차에 해당했다. 애플리케이션 JAR와
단일 사용자 k6 script의 SHA-256은 2-1 snapshot과 동일했다.

```text
application JAR:
5db7f0045bc2da093291978e65b17f729813df6af965f214fc224e1966fbeccb

k6 single-user script:
fc9fce9a4b9d6c5b5b9fc044226a68fc02385080a1749215059304e61cbca87a
```

따라서 runtime 동등성을 확인한 현재 HEAD를 단일 사용자 측정용 manifest로 다시
고정했다.

```text
measurement ID: postgresql-like-single-user-20260804-164537-kst
manifest: benchmark-data/postgresql/like-baseline/single-user-measurement-manifest.txt
manifest SHA-256: 39871d5c5a9f51507b4ae10ee9263e2044b7b6a14b24ba9dd2611b6ddd4d1950
```

##### 실행 조건

```text
smoke: 1 VU, 1 iteration
warm-up: 1 VU, 30초
공식 측정: 1 VU, 60초, 3회
```

Smoke의 네 응답 계약은 모두 통과했다. Warm-up에서는 5,723건을 실행했고 오류와 기능
검증 실패는 없었다. Warm-up 수치는 공식 결과에 포함하지 않는다.

##### 측정 결과

| 실행 | 요청 수 | 실제 처리량 | 평균 | p50 | p95 | p99 | 최대 | HTTP 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1회 | 11,080 | 184.66 RPS | 4.70ms | 4.15ms | 7.40ms | 12.56ms | 157.43ms | 0 |
| 2회 | 12,543 | 209.04 RPS | 4.15ms | 3.96ms | 5.83ms | 8.04ms | 79.85ms | 0 |
| 3회 | 8,408 | 140.13 RPS | 4.13ms | 3.96ms | 5.63ms | 7.68ms | 36.28ms | 0 |
| 실행별 요약값의 중앙값 | 11,080 | 184.66 RPS | 4.15ms | 3.96ms | 5.83ms | 8.04ms | - | 0 |

마지막 행은 세 실행의 전체 요청을 합쳐 percentile을 다시 계산한 값이 아니다. 각
실행에서 계산한 요약값 세 개의 중앙값이다.

세 실행의 총 HTTP 요청 32,031건과 요청당 네 개의 기능 검사 128,124건은 모두
성공했다.

##### 3회차 측정 환경 이상 신호

3회차의 HTTP 최대 응답 시간은 36.28ms였지만 `iteration_duration` 최대값은 약
19.67초였다.

```text
HTTP 최대 응답 시간: 36.28ms
iteration_duration 최대: 19,670.88ms
```

HTTP 요청 자체가 19초 동안 처리된 것은 아니다. k6 프로세스 또는 Docker·호스트
스케줄링이 일시 정지한 신호로 판단했다. 이로 인해 3회차의 요청 수와 RPS가 감소했다.
해당 실행을 사후 제거하지 않고 원본과 결과에 그대로 보존했으며, 한 번의 정지가 대표
요약에 미치는 영향을 제한하기 위해 실행별 결과의 중앙값을 함께 기록했다.

#### 2-2-B. 50 RPS 공식 성능 측정

##### Arrival-rate 조건

단일 사용자 측정은 한 요청이 끝난 뒤 다음 요청을 보내는 closed model이다. 목표 부하
측정은 응답 완료 여부와 독립적으로 초당 50개의 요청 시작을 예약하는 다음 open model을
사용했다.

```text
executor: constant-arrival-rate
target rate: 50 iterations/s
time unit: 1초
duration: 60초
pre-allocated VUs: 50
graceful stop: 10초
공식 반복: 3회
```

공식 실행 전 snapshot을 다시 확인하고 별도 manifest로 고정했다.

```text
measurement ID: postgresql-like-50rps-20260804-201108-kst
manifest: benchmark-data/postgresql/like-baseline/arrival-rate-measurement-manifest.txt
manifest SHA-256: 9ff6ac1693764b8a1e1ed179f973fda6f4a1a38bc615c07551c8f408ac21cc0d

k6 arrival-rate script SHA-256:
b16e3615bfe8ecdfda4465a6814566644c7daa9b2d867c85b626a75071ded8e6
```

Canonical 검증과 `ANALYZE`, 애플리케이션 health, smoke 및 1 VU 30초 warm-up을 다시
실행했다. Warm-up은 5,397건, p50 4.24ms, p95 7.57ms였으며 오류와 기능 검증 실패는
없었다. 이 수치는 공식 50 RPS 결과에 포함하지 않는다.

##### 측정 결과

| 실행 | 완료 요청 | 실제 처리량 | 미시작 요청 | 평균 | p50 | p95 | p99 | 최대 | HTTP 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1회 | 3,001 | 50.005 RPS | 0 | 6.82ms | 6.19ms | 9.68ms | 18.01ms | 98.59ms | 0 |
| 2회 | 3,000 | 49.998 RPS | 0 | 6.81ms | 6.17ms | 9.44ms | 20.88ms | 132.77ms | 0 |
| 3회 | 3,000 | 49.999 RPS | 0 | 6.66ms | 6.02ms | 9.28ms | 17.69ms | 119.06ms | 0 |
| 실행별 요약값의 중앙값 | 3,000 | 49.999 RPS | 0 | 6.81ms | 6.17ms | 9.44ms | 18.01ms | 119.06ms | 0 |

60초와 50 RPS의 이론적인 예약 수는 3,000건이다. 1회차의 3,001건은 시간 경계의
스케줄링 차이이며 누락이 아니다. 세 실행 모두 `dropped_iterations=0`이고 실제 처리량은
목표인 50 RPS를 유지했다.

세 실행에서 총 9,001건의 요청과 36,004개의 기능 검사가 모두 성공했다. 준비한 VU는
50개였지만 실제 동시 활성 VU 최대값은 각 실행에서 7개, 1개, 1개였다. 현재 응답
시간에서는 목표 요청률을 만들기 위해 50개의 동시 실행이 필요하지 않았다는 의미다.

#### 측정 전후 데이터와 원본 검증

두 측정은 각각 실행 전후 canonical 검증 SQL을 실행했다. 모든 검증에서 다음 상태가
유지됐다.

```text
users: 100
posts: 100,000
active posts: 95,000
deleted posts: 5,000
posts per author: 최소 1,000, 최대 1,000

COMMON: 9,500
MEDIUM: 950
RARE: 95
FIXED: 10
SCOPE title: 950
SCOPE content: 950

content length 300~799: 60,000
content length 800~1,999: 30,000
content length 2,000~7,999: 9,000
content length 8,000~15,999: 0
content length 16,000~32,000: 1,000
```

원본 결과는 다음 두 디렉터리에 저장했다.

```text
benchmark-data/postgresql/like-baseline/results/
├── postgresql-like-single-user-20260804-164537-kst/
│   ├── before-state.log
│   ├── smoke.log, smoke.json
│   ├── warmup.log, warmup.json
│   ├── single-user-1~3.log
│   ├── single-user-1~3.json
│   ├── after-state.log
│   └── SHA256SUMS
│
└── postgresql-like-50rps-20260804-201108-kst/
    ├── before-state.log
    ├── smoke.log, smoke.json
    ├── warmup.log, warmup.json
    ├── arrival-rate-1~3.log
    ├── arrival-rate-1~3.json
    ├── after-state.log
    └── SHA256SUMS
```

두 디렉터리의 모든 측정 원본과 두 measurement manifest의 SHA-256 재검증이 통과했다.
측정 종료 후 애플리케이션은 graceful shutdown으로 종료했고 benchmark PostgreSQL과 k6
컨테이너가 남아 있지 않음을 확인했다.

#### 2-2 전체 역검증 결과

- 공식 JAR, PostgreSQL, canonical 데이터, k6 image 및 요청 조건이 snapshot과 일치했다.
- 단일 사용자와 50 RPS 측정 전에 health, smoke 및 warm-up을 통과했다.
- 단일 사용자 1 VU 60초 측정을 3회 완료했다.
- constant-arrival-rate 50 RPS 60초 측정을 3회 완료했다.
- 모든 HTTP 요청과 기능 검사가 성공했다.
- 50 RPS 세 실행에서 시작하지 못한 요청은 0건이었다.
- 측정 전후 canonical 건수, marker 및 본문 길이 분포가 일치했다.
- 두 manifest와 모든 원본 결과의 SHA-256 검증을 통과했다.
- 단일 사용자 3회차의 부하 생성기 정지 신호를 숨기지 않고 기록했다.
- 실행 프로세스와 컨테이너를 정상 종료했다.
- 측정 결과에 영향을 주는 추적 대상 코드 변경은 발생하지 않았다.

따라서 PostgreSQL LIKE의 단일 사용자 및 목표 50 RPS 공식 성능 측정은 완료됐다. H2
기준선과의 정량 비교, 실행 계획과 API 지연 시간의 관계 및 PostgreSQL LIKE 기준선의
최종 해석은 2-3에서 진행한다.
