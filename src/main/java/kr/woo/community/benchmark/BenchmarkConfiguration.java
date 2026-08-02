package kr.woo.community.benchmark;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("benchmark")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BenchmarkGeneratorProperties.class)
public class BenchmarkConfiguration {
}
