package com.cartmate.cartmate.support;

import java.nio.file.Path;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * 검색 테스트가 쓰는 실제 Elasticsearch. nori 플러그인이 든 이미지를 src/test/docker/elasticsearch/Dockerfile로 만들어 한 번 띄운다.
 *
 * <p>{@code @ImportTestcontainers(ProductServiceElasticsearchContainer.class)}로 가져오면 spring.elasticsearch.uris가 이 노드로 잡힌다.
 */
public final class ProductServiceElasticsearchContainer {

    // Gradle 테스트의 작업 폴더가 product-service라서 그 아래 src/test/docker의 Dockerfile을 쓴다
    private static final String IMAGE = new ImageFromDockerfile("cartmate-es-nori", false)
            .withDockerfile(Path.of("src/test/docker/elasticsearch/Dockerfile"))
            .get();

    @ServiceConnection
    static final ElasticsearchContainer ELASTICSEARCH = new SingletonElasticsearchContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"));

    static {
        ELASTICSEARCH.start();
    }

    private ProductServiceElasticsearchContainer() {
    }

    // 테스트 클래스마다 멈추지 않고, JVM이 끝날 때 Testcontainers가 정리한다
    private static final class SingletonElasticsearchContainer extends ElasticsearchContainer {

        private SingletonElasticsearchContainer(DockerImageName image) {
            super(image);
            withEnv("xpack.security.enabled", "false");
            withEnv("ES_JAVA_OPTS", "-Xms448m -Xmx448m");
        }

        @Override
        public void stop() {
        }
    }
}
