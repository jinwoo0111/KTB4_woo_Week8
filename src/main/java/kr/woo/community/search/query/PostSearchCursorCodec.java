package kr.woo.community.search.query;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Component
public class PostSearchCursorCodec {

    private static final int CURSOR_VERSION = 1;
    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MAX_CURSOR_LENGTH = 8_192;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_CONTEXT = "community-post-search-cursor-v1:";
    private static final Base64.Encoder BASE64_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] signingKey;
    private final Duration cursorTtl;
    private final Clock clock;

    @Autowired
    public PostSearchCursorCodec(
            ObjectMapper objectMapper,
            @Value("${app.search.cursor-secret}") String secret,
            @Value("${app.search.cursor-ttl:PT1M}") String cursorTtl
    ) {
        this(objectMapper, secret, Duration.parse(cursorTtl), Clock.systemUTC());
    }

    public PostSearchCursorCodec(
            ObjectMapper objectMapper,
            String secret,
            Duration cursorTtl,
            Clock clock
    ) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "Post search cursor secret must be at least "
                            + MIN_SECRET_LENGTH + " characters"
            );
        }
        if (cursorTtl == null || cursorTtl.isZero() || cursorTtl.isNegative()) {
            throw new IllegalArgumentException("cursorTtl must be positive");
        }
        this.signingKey = sha256(
                (KEY_CONTEXT + secret).getBytes(StandardCharsets.UTF_8)
        );
        this.cursorTtl = cursorTtl;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public String encode(
            String pitId,
            PostSearchCriteria criteria,
            PostSearchSortValues sortValues
    ) {
        validatePitCursorValues(pitId, criteria, sortValues);
        CursorPayload payload = new CursorPayload(
                CURSOR_VERSION,
                pitId,
                criteriaFingerprint(criteria),
                criteria.sort().name(),
                sortValues.relevanceScore(),
                sortValues.postId(),
                sortValues.pitShardDoc(),
                Instant.now(clock).plus(cursorTtl).toEpochMilli()
        );

        try {
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            return BASE64_ENCODER.encodeToString(payloadBytes)
                    + "."
                    + BASE64_ENCODER.encodeToString(sign(payloadBytes));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to encode post search cursor", e);
        }
    }

    public DecodedPostSearchCursor decode(
            String cursor,
            PostSearchCriteria criteria
    ) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidPostSearchCursorException(
                    "Post search cursor must not be blank"
            );
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new InvalidPostSearchCursorException(
                    "Post search cursor is too long"
            );
        }
        java.util.Objects.requireNonNull(criteria, "criteria");

        String[] segments = cursor.split("\\.", -1);
        if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            throw new InvalidPostSearchCursorException("Malformed post search cursor");
        }

        try {
            byte[] payloadBytes = BASE64_DECODER.decode(segments[0]);
            byte[] providedSignature = BASE64_DECODER.decode(segments[1]);
            byte[] expectedSignature = sign(payloadBytes);
            if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
                throw new InvalidPostSearchCursorException(
                        "Invalid post search cursor signature"
                );
            }

            CursorPayload payload = objectMapper.readValue(
                    payloadBytes,
                    CursorPayload.class
            );
            validatePayload(payload, criteria);
            PostSearchSortValues sortValues = new PostSearchSortValues(
                    payload.relevanceScore(),
                    payload.postId(),
                    payload.pitShardDoc()
            );
            return new DecodedPostSearchCursor(payload.pitId(), sortValues);
        } catch (IllegalArgumentException e) {
            if (e instanceof InvalidPostSearchCursorException invalidCursor) {
                throw invalidCursor;
            }
            throw new InvalidPostSearchCursorException(
                    "Malformed post search cursor",
                    e
            );
        } catch (JacksonException e) {
            throw new InvalidPostSearchCursorException(
                    "Malformed post search cursor payload",
                    e
            );
        }
    }

    private void validatePayload(CursorPayload payload, PostSearchCriteria criteria) {
        if (payload.version() != CURSOR_VERSION
                || payload.pitId() == null
                || payload.pitId().isBlank()
                || !criteriaFingerprint(criteria).equals(payload.criteriaFingerprint())
                || !criteria.sort().name().equals(payload.sort())
                || payload.postId() <= 0
                || payload.pitShardDoc() == null
                || payload.pitShardDoc() < 0) {
            throw new InvalidPostSearchCursorException(
                    "Post search cursor does not match the search request"
            );
        }
        if (criteria.sort() == PostSearchSort.TIME
                && payload.relevanceScore() != null) {
            throw new InvalidPostSearchCursorException(
                    "Time cursor must not contain a relevance score"
            );
        }
        if (criteria.sort() == PostSearchSort.RELEVANCE
                && (payload.relevanceScore() == null
                || !Double.isFinite(payload.relevanceScore())
                || payload.relevanceScore() < 0)) {
            throw new InvalidPostSearchCursorException(
                    "Relevance cursor requires a valid score"
            );
        }
        if (payload.expiresAtEpochMilli() <= Instant.now(clock).toEpochMilli()) {
            throw new ExpiredPostSearchCursorException();
        }
    }

    private void validatePitCursorValues(
            String pitId,
            PostSearchCriteria criteria,
            PostSearchSortValues sortValues
    ) {
        if (pitId == null || pitId.isBlank()) {
            throw new IllegalArgumentException("pitId must not be blank");
        }
        java.util.Objects.requireNonNull(criteria, "criteria");
        java.util.Objects.requireNonNull(sortValues, "sortValues");
        if (sortValues.pitShardDoc() == null) {
            throw new IllegalArgumentException("PIT cursor requires pitShardDoc");
        }
        if (criteria.sort() == PostSearchSort.TIME
                && sortValues.relevanceScore() != null) {
            throw new IllegalArgumentException(
                    "Time cursor must not contain relevanceScore"
            );
        }
        if (criteria.sort() == PostSearchSort.RELEVANCE
                && sortValues.relevanceScore() == null) {
            throw new IllegalArgumentException(
                    "Relevance cursor requires relevanceScore"
            );
        }
    }

    private String criteriaFingerprint(PostSearchCriteria criteria) {
        String canonical = criteria.keyword().length()
                + ":" + criteria.keyword()
                + "|" + criteria.scope().name()
                + "|" + criteria.sort().name();
        return BASE64_ENCODER.encodeToString(
                sha256(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record CursorPayload(
            int version,
            String pitId,
            String criteriaFingerprint,
            String sort,
            Double relevanceScore,
            long postId,
            Long pitShardDoc,
            long expiresAtEpochMilli
    ) {
    }
}
