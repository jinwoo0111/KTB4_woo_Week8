import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
    },
};

export default function () {
    const baseUrl =
        __ENV.BASE_URL || 'http://host.docker.internal:18084';

    const response = http.get(
        `${baseUrl}/posts?keyword=qzcommona91x&scope=all&size=10`,
        {
            tags: {
                search_case: 'common_all',
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
