# 하루한판 실행 이미지. jar 는 밖에서 만든다 (GitHub Actions: ./gradlew bootJar → build/libs/haruhanpan.jar)
# 로컬에서:  ./gradlew bootJar && docker build -t haruhanpan .
FROM eclipse-temurin:21-jre

# 서버의 /var/lib/haruhanpan 소유자(haruhanpan, uid 985)와 같은 uid 로 실행해 백업·로그인 유지 키를 그대로 쓴다
# (실제 uid 는 compose.yml 의 user 로 지정한다. 여기 값은 기본값)
RUN groupadd --system --gid 983 haruhanpan && useradd --system --uid 985 --gid 983 --no-create-home haruhanpan

WORKDIR /app
COPY build/libs/haruhanpan.jar /app/haruhanpan.jar

# 컨테이너 안에서는 모든 주소로 받는다. 서버 밖 노출은 compose 가 127.0.0.1 로만 연다 (그 앞은 Caddy)
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_OPTS="-Xms64m -Xmx384m -XX:+UseSerialGC"
EXPOSE 8765
USER haruhanpan
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/haruhanpan.jar"]
