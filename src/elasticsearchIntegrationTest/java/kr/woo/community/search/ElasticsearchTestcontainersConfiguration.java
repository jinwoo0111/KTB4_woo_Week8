package kr.woo.community.search;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

@TestConfiguration(proxyBeanMethods = false)
public class ElasticsearchTestcontainersConfiguration {

    private static final String ELASTICSEARCH_VERSION = "9.2.8";

    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearchContainer() {
        ImageFromDockerfile image = new ImageFromDockerfile(
                "community-elasticsearch-nori-test:" + ELASTICSEARCH_VERSION,
                false
        )
                .withDockerfile(Path.of("docker/elasticsearch/Dockerfile").toAbsolutePath())
                .withBuildArg("ELASTICSEARCH_VERSION", ELASTICSEARCH_VERSION);

        DockerImageName imageName = DockerImageName.parse(image.get())
                .asCompatibleSubstituteFor(
                        "docker.elastic.co/elasticsearch/elasticsearch"
                );

        return new ElasticsearchContainer(imageName)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("xpack.security.http.ssl.enabled", "false");
    }
}
