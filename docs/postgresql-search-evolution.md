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
| 1-4 | canonical benchmark dataset 및 dump·restore 기반 구축 | 진행 전 |

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

## 5. 현재 상태와 다음 작업

### 5.1. 1-3 완료 상태

PostgreSQL 실행 기반, 기본 스키마와 기존 기능·benchmark 데이터 동등성 검증을
완료했다.

```text
PostgreSQL 18.4 이전 기반
├── local·benchmark Compose 환경
├── Flyway V1과 Hibernate validate
├── 명시적 sequence
├── Testcontainers PostgreSQL 통합 테스트
├── 기존 기능 동등성 검증
└── PostgreSQL benchmark generator와 1천 건 데이터 동등성 검증
```

현재 검색 방식은 기존 JPA `LOWER(column) LIKE '%keyword%'` 쿼리를 유지한다.
PostgreSQL에서 `LIKE` 기준 성능을 측정하거나 `pg_trgm`, Full Text Search를 적용한
상태는 아니다.

### 5.2. 남은 경계

다음 항목은 아직 구현하지 않았다.

```text
10만 건 canonical dataset
data-only dump와 checksum
dump restore 후 sequence 검증과 ANALYZE
PostgreSQL LIKE 기준 성능 측정
pg_trgm 적용·측정·제거
Full Text Search 적용·측정·제거
최종 검색 방식 결정
```

### 5.3. 다음 단계

다음 단계는 canonical benchmark dataset 및 dump·restore 기반 구축이다.

1. 빈 V1 상태의 benchmark PostgreSQL에 고정 설정으로 10만 건을 생성한다.
2. 건수, 작성자, 삭제, marker 및 본문 길이 분포를 검증한다.
3. 검증된 데이터를 data-only dump로 만들고 checksum을 기록한다.
4. 빈 V1 DB에 복원해 데이터, sequence 및 통계를 검증한다.

이 기반을 완성한 뒤 PostgreSQL 기본 `LIKE`, `pg_trgm` 및 Full Text Search를 동일한
데이터와 부하 조건에서 비교한다.
