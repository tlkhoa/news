package com.tdk.news;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.CommandLineRunner;
import com.tdk.news.service.RssCollectionService;
import java.util.concurrent.CompletableFuture;

/**
 * Background Worker configuration cho Render (Background Worker service).
 * Chỉ được kích hoạt khi khởi động với profile "worker".
 * Vòng lặp while(true): thu thập tin mỗi 6 giờ, sau đó nghỉ.
 * Class này được NewsApplication component-scan khi chạy với profile worker.
 */
@Configuration
@Profile("worker")
public class NewsCollectorWorker {

    @Bean
    public CommandLineRunner runPeriodically(RssCollectionService collectionService) {
        return args -> {
            System.out.println("[WORKER] Đã khởi động Background Worker thu thập tin.");
            while (true) {
                try {
                    System.out.println("[WORKER] [" + java.time.LocalDateTime.now()
                            + "] Bắt đầu thu thập tin...");

                    // Gọi collect trong nền (dùng thread pool của Spring @Async),
                    // main thread chờ cho đến khi xong một lượt rồi mới nghỉ.
                    CompletableFuture<?> future = collectionService.collectTodayAsync(null);
                    future.join();

                    System.out.println("[WORKER] Hoàn tất lượt thu thập. Nghỉ 6 giờ...");
                } catch (Exception e) {
                    System.err.println("[WORKER] Lỗi khi thu thập: " + e.getMessage());
                    e.printStackTrace();
                }

                // Nghỉ 6 giờ (21.600.000 ms) trước lượt tiếp theo
                try {
                    Thread.sleep(6L * 60 * 60 * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println("[WORKER] Đã nhận tín hiệu dừng, thoát worker.");
                    break;
                }
            }
        };
    }
}
