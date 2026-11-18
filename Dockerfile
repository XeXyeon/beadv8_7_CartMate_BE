# 서비스 이미지. 서비스마다 SERVICE 빌드 인자만 바꿔서 같은 파일로 만든다. 빌드 컨텍스트는 저장소 루트다
#   docker build --build-arg SERVICE=order-service -t cartmate-order-service .
# SERVICE: member-service · product-service · order-service · payment-service · etc-service
#
# 베이스 이미지는 정확한 버전(Java 25.0.5+8, Alpine 3.24)과 digest로 고정한다
# 25-jdk-alpine 같은 태그는 Java 패치 · Alpine 버전이 올라가면 같은 이름이 다른 이미지를 가리킨다
FROM eclipse-temurin:25.0.5_8-jdk-alpine-3.24@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS builder
ARG SERVICE
RUN test -n "$SERVICE" || (echo "SERVICE 빌드 인자가 없다 (예: --build-arg SERVICE=order-service)" && exit 1)
WORKDIR /app

# settings.gradle이 모든 모듈을 include하므로 빌드 설정 파일은 전부 넣는다. 의존성만 먼저 받아 레이어 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY common/build.gradle common/
COPY member-service/build.gradle member-service/
COPY product-service/build.gradle product-service/
COPY order-service/build.gradle order-service/
COPY payment-service/build.gradle payment-service/
COPY ai-service/build.gradle ai-service/
COPY etc-service/build.gradle etc-service/
# Windows에서 받은 gradlew는 줄 끝이 CRLF라 리눅스에서 실행되지 않는다. LF로 바꾼 뒤 실행한다
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew :${SERVICE}:dependencies --no-daemon > /dev/null

COPY common/src common/src
COPY ${SERVICE}/src/main ${SERVICE}/src/main
RUN ./gradlew :${SERVICE}:bootJar --no-daemon

FROM eclipse-temurin:25.0.5_8-jre-alpine-3.24@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
ARG SERVICE
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder --chown=app:app /app/${SERVICE}/build/libs/${SERVICE}-*-SNAPSHOT.jar app.jar
USER app

# 메모리 제한(컨테이너 limit)의 60%를 힙 최대치로 쓴다. 나머지는 Metaspace · 스레드 스택 · 코드 캐시 등 힙 밖 영역
# OOM이 나면 바로 종료해서 k3s가 Pod를 다시 띄우게 한다
ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul"

ENTRYPOINT ["java", "-jar", "app.jar"]
