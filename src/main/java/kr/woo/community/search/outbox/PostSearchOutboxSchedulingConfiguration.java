package kr.woo.community.search.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "app.search.indexing-enabled",
        havingValue = "true"
)
public class PostSearchOutboxSchedulingConfiguration {
}
