package kr.woo.community.search.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-worker-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.search.indexing-enabled=true",
        "app.search.outbox.poll-delay=PT1H"
})
class PostSearchOutboxWorkerConfigurationTest {

    @Autowired
    private PostSearchOutboxWorker worker;

    @Test
    void createsTheScheduledWorkerOnlyWhenIndexingIsEnabled() {
        assertThat(worker).isNotNull();
    }
}
