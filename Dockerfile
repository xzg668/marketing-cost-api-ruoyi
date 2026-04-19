FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY yudao-framework ./yudao-framework
COPY marketing-cost-biz/pom.xml ./marketing-cost-biz/pom.xml
COPY maven-settings.xml /root/.m2/settings.xml
COPY marketing-cost-biz/src ./marketing-cost-biz/src

RUN mvn -s /root/.m2/settings.xml -B -DskipTests package \
  && JAR_PATH=$(ls -1 marketing-cost-biz/target/*.jar | grep -v "original" | head -n 1) \
  && cp "$JAR_PATH" /app/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
