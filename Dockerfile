FROM openjdk:11          

VOLUME /tmp                   
EXPOSE 80                   
ADD target/news-0.0.1-SNAPSHOT.jar news-0.0.1-SNAPSHOT.jar
ENTRYPOINT ["java","-jar","/news-0.0.1-SNAPSHOT.jar"]  