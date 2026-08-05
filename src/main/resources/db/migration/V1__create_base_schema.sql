CREATE SEQUENCE users_seq
    START WITH 1
    INCREMENT BY 50;

CREATE SEQUENCE posts_seq
    START WITH 1
    INCREMENT BY 50;

CREATE SEQUENCE comments_seq
    START WITH 1
    INCREMENT BY 50;

CREATE SEQUENCE post_likes_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE users
(
    user_id       BIGINT       NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    nickname      VARCHAR(255) NOT NULL,
    profile_image VARCHAR(255),
    role          VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITHOUT TIME ZONE,
    deleted_at    TIMESTAMP(6) WITHOUT TIME ZONE,

    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_nickname UNIQUE (nickname),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE posts
(
    post_id       BIGINT         NOT NULL,
    user_id       BIGINT         NOT NULL,
    title         VARCHAR(255)   NOT NULL,
    content       VARCHAR(32000) NOT NULL,
    content_image VARCHAR(255),
    like_count    INTEGER        NOT NULL DEFAULT 0,
    comment_count INTEGER        NOT NULL DEFAULT 0,
    view_count    INTEGER        NOT NULL DEFAULT 0,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITHOUT TIME ZONE,
    deleted_at    TIMESTAMP(6) WITHOUT TIME ZONE,

    CONSTRAINT pk_posts PRIMARY KEY (post_id),
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT ck_posts_like_count CHECK (like_count >= 0),
    CONSTRAINT ck_posts_comment_count CHECK (comment_count >= 0),
    CONSTRAINT ck_posts_view_count CHECK (view_count >= 0)
);

CREATE TABLE comments
(
    comment_id BIGINT       NOT NULL,
    post_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE,
    deleted_at TIMESTAMP(6) WITHOUT TIME ZONE,

    CONSTRAINT pk_comments PRIMARY KEY (comment_id),
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (post_id),
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE TABLE post_likes
(
    post_like_id BIGINT NOT NULL,
    post_id      BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    created_at   TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,

    CONSTRAINT pk_post_likes PRIMARY KEY (post_like_id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (post_id),
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uk_post_likes_post_user UNIQUE (post_id, user_id)
);

CREATE INDEX idx_posts_user_id
    ON posts (user_id);

CREATE INDEX idx_posts_active_cursor
    ON posts (post_id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_comments_user_id
    ON comments (user_id);

CREATE INDEX idx_comments_active_post
    ON comments (post_id, comment_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_post_likes_user_id
    ON post_likes (user_id);
