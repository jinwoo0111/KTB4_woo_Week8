package kr.woo.community.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public class BenchmarkPostDataFactory {
    private static final int DELETION_INTERVAL = 20;
    private static final int LENGTH_DISTRIBUTION_BLOCK_SIZE = 100;
    private static final int LENGTH_BUCKET_MULTIPLIER = 37;
    private static final double MARKER_POSITION_RATIO = 0.85;

    private static final long LENGTH_DISTRIBUTION_SALT = 0x243F6A8885A308D3L;
    private static final long TITLE_LENGTH_SALT = 0x13198A2E03707344L;
    private static final long CONTENT_LENGTH_SALT = 0xA4093822299F31D0L;
    private static final long TITLE_TEXT_SALT = 0x082EFA98EC4E6C89L;
    private static final long CONTENT_TEXT_SALT = 0x452821E638D01377L;

    private static final String[] WORDS = {
            "커뮤니티", "게시글", "사용자", "서비스", "기능", "개발", "데이터", "검색",
            "성능", "테스트", "스프링", "자바", "데이터베이스", "백엔드", "프론트엔드",
            "경험", "프로젝트", "학습", "설계", "구현", "검증", "기록", "질문", "답변",
            "오늘", "내일", "생각", "정보", "내용", "공유", "도움", "문제", "해결"
    };

    private final long seed;
    private final int postCount;
    private final int authorCount;
    private final long activePostCount;

    public BenchmarkPostDataFactory(long seed, int postCount, int authorCount) {
        if (postCount <= 0) {
            throw new IllegalArgumentException("postCount must be positive");
        }
        if (authorCount <= 0) {
            throw new IllegalArgumentException("authorCount must be positive");
        }

        this.seed = seed;
        this.postCount = postCount;
        this.authorCount = authorCount;
        this.activePostCount = postCount - (postCount / DELETION_INTERVAL);
    }

    public BenchmarkPostData create(long sequence) {
        if (sequence < 1 || sequence > postCount) {
            throw new IllegalArgumentException("sequence must be between 1 and postCount");
        }

        boolean deleted = sequence % DELETION_INTERVAL == 0;
        long activeSequence = deleted ? 0 : sequence - (sequence / DELETION_INTERVAL);
        int authorIndex = (int) ((sequence - 1) % authorCount);

        List<String> contentMarkers = deleted
                ? List.of()
                : resolveContentMarkers(activeSequence);
        List<String> titleMarkers = contentMarkers.contains(BenchmarkSearchMarkers.SCOPE)
                ? List.of(BenchmarkSearchMarkers.SCOPE)
                : List.of();

        int titleLength = randomInRange(sequence, TITLE_LENGTH_SALT, 20, 80);
        int contentLength = resolveContentLength(sequence);

        String title = createText(
                titleLength,
                titleMarkers,
                random(sequence, TITLE_TEXT_SALT)
        );
        String content = createText(
                contentLength,
                contentMarkers,
                random(sequence, CONTENT_TEXT_SALT)
        );

        return new BenchmarkPostData(
                sequence,
                activeSequence,
                authorIndex,
                title,
                content,
                deleted
        );
    }

    public long getActivePostCount() {
        return activePostCount;
    }

    private List<String> resolveContentMarkers(long activeSequence) {
        List<String> markers = new ArrayList<>();

        if (activeSequence % 10 == 0) {
            markers.add(BenchmarkSearchMarkers.COMMON);
        }
        if (activeSequence % 100 == 0) {
            markers.add(BenchmarkSearchMarkers.MEDIUM);
            markers.add(BenchmarkSearchMarkers.SCOPE);
        }
        if (activeSequence % 1_000 == 0) {
            markers.add(BenchmarkSearchMarkers.RARE);
        }
        if (isFixedMarkerPosition(activeSequence)) {
            markers.add(BenchmarkSearchMarkers.FIXED);
        }

        return markers;
    }

    private boolean isFixedMarkerPosition(long activeSequence) {
        for (int markerNumber = 1; markerNumber <= 10; markerNumber++) {
            long position = (markerNumber * activePostCount + 9) / 10;
            if (activeSequence == position) {
                return true;
            }
        }
        return false;
    }

    private int resolveContentLength(long sequence) {
        int bucket = resolveLengthBucket(sequence);

        if (bucket < 60) {
            return randomInRange(sequence, CONTENT_LENGTH_SALT, 300, 799);
        }
        if (bucket < 90) {
            return randomInRange(sequence, CONTENT_LENGTH_SALT, 800, 1_999);
        }
        if (bucket < 99) {
            return randomInRange(sequence, CONTENT_LENGTH_SALT, 2_000, 7_999);
        }
        return randomInRange(sequence, CONTENT_LENGTH_SALT, 16_000, 32_000);
    }

    private int resolveLengthBucket(long sequence) {
        long zeroBasedSequence = sequence - 1;
        long block = zeroBasedSequence / LENGTH_DISTRIBUTION_BLOCK_SIZE;
        int positionInBlock = (int) (zeroBasedSequence % LENGTH_DISTRIBUTION_BLOCK_SIZE);
        int offset = random(block + 1, LENGTH_DISTRIBUTION_SALT)
                .nextInt(LENGTH_DISTRIBUTION_BLOCK_SIZE);

        return Math.floorMod(
                positionInBlock * LENGTH_BUCKET_MULTIPLIER + offset,
                LENGTH_DISTRIBUTION_BLOCK_SIZE
        );
    }

    private int randomInRange(long sequence, long salt, int minInclusive, int maxInclusive) {
        return random(sequence, salt).nextInt(minInclusive, maxInclusive + 1);
    }

    private SplittableRandom random(long sequence, long salt) {
        long mixedSeed = seed ^ salt ^ (sequence * 0x9E3779B97F4A7C15L);
        return new SplittableRandom(mixedSeed);
    }

    private String createText(
            int targetLength,
            List<String> markers,
            SplittableRandom random
    ) {
        String markerBlock = String.join(" ", markers);
        int separatorLength = markerBlock.isEmpty() ? 0 : 2;
        int fillerLength = targetLength - markerBlock.length() - separatorLength;

        if (fillerLength < 0) {
            throw new IllegalStateException("Markers are longer than the target text length");
        }

        String filler = createFiller(fillerLength, random);

        if (markerBlock.isEmpty()) {
            return filler;
        }

        int markerPosition = (int) Math.floor(filler.length() * MARKER_POSITION_RATIO);

        return filler.substring(0, markerPosition)
                + " "
                + markerBlock
                + " "
                + filler.substring(markerPosition);
    }

    private String createFiller(int targetLength, SplittableRandom random) {
        StringBuilder builder = new StringBuilder(targetLength);

        while (builder.length() < targetLength) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(WORDS[random.nextInt(WORDS.length)]);
        }

        return builder.substring(0, targetLength);
    }
}
