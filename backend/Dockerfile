# API 서버 이미지.
#
# **이 서버는 서버리스에 올리면 안 됩니다.** 정보나루에 등록할 고정 IP 가 여기에 붙어야
# 하는데, 서버리스는 나가는 IP 가 고정되지 않아 등록 효과를 받지 못합니다. 그러면 조용히
# 1일 500건 한도가 적용되고, 평소에는 멀쩡하다가 여러 권 확인 두어 번에 소진되어
# "확인 불가"가 쏟아지는 형태로 나타납니다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 래퍼와 의존성 정의를 먼저 넣어 레이어 캐시를 살립니다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle* ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test --quiet

FROM eclipse-temurin:21-jre
WORKDIR /app

# 루트로 돌리지 않습니다.
RUN useradd --system --create-home --shell /usr/sbin/nologin wimb
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER wimb

EXPOSE 8080

# D4L_AUTH_KEY 는 이미지에 굽지 않고 실행할 때 환경 변수로 넣습니다.
# 비어 있으면 서버가 그 사실을 말하고 뜨지 않습니다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
