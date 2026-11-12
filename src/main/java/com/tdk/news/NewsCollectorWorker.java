//package com.tdk.news;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Profile;
//import org.springframework.boot.CommandLineRunner;
//import com.tdk.news.service.RssCollectionService;
//import java.util.concurrent.CompletableFuture;
//
///**
// * Background Worker configuration cho Render (Background Worker service).
// * Chỉ được kích hoạt khi khởi động với profile "worker".
// * Vòng lặp while(true): thu thập tin mỗi 6 giờ, sau đó nghỉ.
// * Class này được NewsApplication component-scan khi chạy với profile worker.
// */
//@Configuration
//@Profile("worker")
//public class NewsCollectorWorker {
//
//    private static final Logger log = LoggerFactory.getLogger(NewsCollectorWorker.class);
//
//    @Bean
//    public CommandLineRunner runPeriodically(RssCollectionService collectionService) {
//        return args -> {
//            log.info("[WORKER] Đã khởi động Background Worker thu thập tin.");
//            while (true) {
//                try {
//                    log.info("[WORKER] [{}] Bắt đầu thu thập tin...", java.time.LocalDateTime.now());
//
//                    // Gọi collect trong nền (dùng thread pool của Spring @Async),
//                    // main thread chờ cho đến khi xong một lượt rồi mới nghỉ.
//                    CompletableFuture<?> future = collectionService.collectTodayAsync(null);
//                    future.join();
//
//                    log.info("[WORKER] Hoàn tất lượt thu thập. Nghỉ 6 giờ...");
//                } catch (Exception e) {
//                    log.error("[WORKER] Lỗi khi thu thập: {}", e.getMessage(), e);
//                }
//
//                // Nghỉ 6 giờ (21.600.000 ms) trước lượt tiếp theo
//                try {
//                    Thread.sleep(6L * 60 * 60 * 1000);
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                    log.info("[WORKER] Đã nhận tín hiệu dừng, thoát worker.");
//                    break;
//                }
//            }
//        };
//    }
//}
