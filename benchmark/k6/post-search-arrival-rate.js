import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 50);
const duration = __ENV.DURATION || '60s';
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || 50);

if (!Number.isInteger(rate) || rate <= 0) {
    throw new Error('RATE는 1 이상의 정수여야 합니다.');
}

if (!Number.isInteger(preAllocatedVUs) || preAllocatedVUs <= 0) {
    throw new Error('PREALLOCATED_VUS는 1 이상의 정수여야 합니다.');
}

export const options = {
    scenarios: {
        search_load: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration,
            preAllocatedVUs,
            gracefulStop: '10s',
        },
    },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
    },
    summaryTrendStats: [
        'avg',
        'min',
        'med',
        'max',
        'p(90)',
        'p(95)',
        'p(99)',
    ],
};

export default function () {
    const baseUrl =
        __ENV.BASE_URL || 'http://host.docker.internal:18084';

    const response = http.get(
        `${baseUrl}/posts?keyword=qzcommona91x&scope=all&size=10`,
        {
            tags: {
                search_case: 'common_all',
                target_rps: String(rate),
            },
        },
    );

    let responseBody = null;

    try {
        responseBody = response.json();
    } catch (error) {
        responseBody = null;
    }

    const responseData =
        responseBody !== null && responseBody.data !== undefined
            ? responseBody.data
            : null;

    check(response, {
        'HTTP 상태가 200이다': (res) => res.status === 200,

        '응답이 JSON이다': () => responseBody !== null,

        '게시글이 10건 반환된다': () =>
            responseData !== null &&
            responseData.count === 10 &&
            Array.isArray(responseData.posts) &&
            responseData.posts.length === 10,

        '다음 페이지가 존재한다': () =>
            responseData !== null &&
            responseData.has_next === true &&
            responseData.next_cursor !== null,
    });
}
