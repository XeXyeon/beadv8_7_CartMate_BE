package com.cartmate.cartmate.shared;

import com.cartmate.cartmate.global.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모듈 경계를 넘는 이벤트는 같은 서비스 안이어도 전부 DomainEvent(Outbox → Kafka)로 보낸다.
 * Spring 이벤트로만 보내면 서비스를 떼어 낸 뒤 받는 쪽에 도착하지 않는다 (판매자 등록이 결제·상품에 안 가던 버그).
 */
class SharedEventsAreDomainEventsTest {

    private static final String SHARED_PACKAGE = "com.cartmate.cartmate.shared";

    // 같은 모듈 안에서만 쓰는 이벤트를 shared에 둘 수밖에 없을 때만, 이유 주석과 함께 여기에 넣는다. 지금은 없다.
    private static final Set<String> SAME_MODULE_ONLY = Set.of();

    @Test
    void shared_event_패키지의_이벤트는_모두_DomainEvent다() {
        List<String> notDomainEvents = sharedEventClasses().stream()
                .filter(type -> !SAME_MODULE_ONLY.contains(type.getName()))
                .filter(type -> !DomainEvent.class.isAssignableFrom(type))
                .map(Class::getName)
                .toList();

        assertThat(notDomainEvents)
                .as("shared.<모듈>.event의 이벤트는 DomainEvent를 구현하고 DomainEventPublisher(Outbox)로 발행해야 한다")
                .isEmpty();
    }

    @Test
    void 검사_대상을_실제로_찾는다() {
        assertThat(sharedEventClasses()).extracting(Class::getSimpleName)
                .contains("SellerRegisteredEvent", "SalesPostHiddenEvent", "DeliveryClaimCompletedEvent");
    }

    // shared.<모듈>.event 패키지의 최상위 클래스 중 이름이 Event로 끝나는 것 (PendingReturn 같은 DTO · enum은 제외)
    private static List<Class<?>> sharedEventClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> {
            String className = reader.getClassMetadata().getClassName();
            String packageName = ClassUtils.getPackageName(className);
            return packageName.endsWith(".event")
                    && className.endsWith("Event")
                    && !reader.getClassMetadata().isInterface()
                    && !reader.getClassMetadata().hasEnclosingClass();
        });
        return scanner.findCandidateComponents(SHARED_PACKAGE).stream()
                .map(definition -> load(definition.getBeanClassName()))
                .<Class<?>>map(type -> type)
                .toList();
    }

    private static Class<?> load(String className) {
        try {
            return ClassUtils.forName(className, SharedEventsAreDomainEventsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
