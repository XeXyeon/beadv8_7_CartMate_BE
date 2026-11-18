package com.cartmate.cartmate.global.global;

import com.cartmate.cartmate.global.lock.DistributedLockAspect;
import com.cartmate.cartmate.global.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

// S3 · Redisson은 common이 컴파일만 하고, 쓰는 서비스만 의존성을 넣는다.
// 의존성이 없는 서비스에서는 관련 빈(S3Config · RedissonConfig · 분산 락)이 만들어지지 않아야 한다
class OptionalDependencyConditionTest {

    private final ApplicationContextRunner redissonRunner = new ApplicationContextRunner()
            .withBean(RedisProperties.class, () -> new RedisProperties("", 1,
                    new RedisProperties.Node("localhost", 6379), new RedisProperties.Node("localhost", 6380)))
            .withUserConfiguration(RedissonConfig.class, DistributedLockExecutor.class, DistributedLockAspect.class);

    private final ApplicationContextRunner s3Runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withPropertyValues("storage.s3.region=ap-northeast-2")
            .withUserConfiguration(S3Config.class);

    // payment · etc: Redisson이 있으면 락 빈이 만들어진다. RedissonClient는 @Lazy라 Redis에 접속하지 않는다
    @Test
    void redissonPresent_createsLockBeans() {
        redissonRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedissonConfig.class);
            assertThat(context).hasSingleBean(DistributedLockExecutor.class);
            assertThat(context).hasSingleBean(DistributedLockAspect.class);
        });
    }

    // member · product · order: Redisson이 없으면 락 빈을 건너뛰고 기동은 정상이다
    @Test
    void redissonAbsent_skipsLockBeans() {
        redissonRunner.withClassLoader(new FilteredClassLoader(RedissonClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RedissonConfig.class);
            assertThat(context).doesNotHaveBean(DistributedLockExecutor.class);
            assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
        });
    }

    // product: AWS SDK가 있으면 운영에서 S3Client를 만든다
    @Test
    void s3Present_createsS3Client() {
        s3Runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3Client.class);
        });
    }

    // member · order · payment · etc: AWS SDK가 없으면 운영에서도 S3Client를 만들지 않는다
    @Test
    void s3Absent_skipsS3Client() {
        s3Runner.withClassLoader(new FilteredClassLoader(S3Client.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(S3Config.class);
        });
    }
}
