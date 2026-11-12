package com.tdk.news.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class KeepAliveService {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveService.class);

    // Biến toàn cục dùng chung cho mọi session, mặc định là false (chưa chạy)
    private final AtomicBoolean isKeepAliveActive = new AtomicBoolean(false);
    private final RestTemplate restTemplate = new RestTemplate();

    // Trả về trạng thái keep-alive hiện tại (dùng hiển thị trên trang admin).
    public boolean isKeepAliveActive() {
        return isKeepAliveActive.get();
    }

    // Hàm gọi ở đầu API update DB để bật cờ
    public void activateKeepAlive() {
        isKeepAliveActive.set(true);
    }

    // Hàm tùy chọn: tắt cờ nếu sau này bạn không muốn giữ server thức nữa
    public void deactivateKeepAlive() {
        isKeepAliveActive.set(false);
    }

    // Chạy kiểm tra mỗi 10 phút (600,000 mili-giây)
    @Scheduled(fixedRate = 600000)
    public void pingSelf() {
        if (isKeepAliveActive.get()) {
            try {
                // Bắt buộc dùng URL công khai của Render
                String url = "https://news-2p7c.onrender.com";
                restTemplate.getForObject(url, String.class);
                log.info("Đã tự ping thành công để giữ server thức!");
            } catch (Exception e) {
                log.error("Lỗi khi tự ping: " + e.getMessage());
            }
        }
    }
}