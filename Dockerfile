FROM openjdk:17              

VOLUME /tmp                   
EXPOSE 80                   
ADD target/springboot-notify-weather-0.0.1-SNAPSHOT.jar springboot-notify-weather-0.0.1-SNAPSHOT.jar 
ENTRYPOINT ["java","-jar","/springboot-notify-weather-0.0.1-SNAPSHOT.jar"]  