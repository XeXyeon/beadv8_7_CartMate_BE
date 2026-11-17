package com.cartmate.cartmate.global.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

// AWS SDK(S3)를 의존성으로 넣은 서비스(product)에서만 S3Client를 만든다. 다른 서비스는 SDK 클래스가 없어 건너뛴다
@Configuration
@Profile("prod")
@ConditionalOnClass(name = "software.amazon.awssdk.services.s3.S3Client")
public class S3Config {

    @Value("${storage.s3.region}")
    private String region;

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}