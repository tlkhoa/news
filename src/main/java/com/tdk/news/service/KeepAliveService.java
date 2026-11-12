package com.tdk.news.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KeepAliveService {

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
                System.out.println("Đã tự ping thành công để giữ server thức!");
            } catch (Exception e) {
                System.err.println("Lỗi khi tự ping: " + e.getMessage());
            }
        }
    }
}