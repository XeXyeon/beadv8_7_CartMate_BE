package com.cartmate.cartmate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

// 메모리 최적화로 바꾼 설정 파일 값이 그대로 잡히는지
class MemorySettingsConfigTest {

    // Tomcat 최대 요청 스레드: 기본 200 → 50
    @Test
    void tomcatMaxThreads_is50() throws IOException {
        StandardEnvironment environment = load("application.yaml");

        assertThat(environment.getProperty("server.tomcat.threads.max", Integer.class)).isEqualTo(50);
    }

    // 운영(prod)에서는 springdoc API 문서와 Swagger UI를 끈다
    @Test
    void prodProfile_disablesSpringdoc() throws IOException {
        StandardEnvironment environment = load("application-prod.yaml");

        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
    }

    private StandardEnvironment load(String file) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(file, new ClassPathResource(file))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }
}
