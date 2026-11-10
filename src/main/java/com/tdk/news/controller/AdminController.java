package com.tdk.news.controller;

import com.tdk.news.repository.SourceRepository;
import com.tdk.news.service.RssCollectionService;
import com.tdk.news.service.RssCollectionService.CollectionResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {

    private final RssCollectionService collectionService;
    private final SourceRepository sourceRepository;

    public AdminController(RssCollectionService collectionService, SourceRepository sourceRepository) {
        this.collectionService = collectionService;
        this.sourceRepository = sourceRepository;
    }

    /** Trang quản trị: danh sách nguồn và nút cập nhật tin hôm nay. */
    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("pageTitle", "Quản trị");
        model.addAttribute("sources", sourceRepository.findAll());
        // lastResult được thêm bởi flash attribute sau khi POST; nếu chưa có thì để null.
        return "admin";
    }

    /** Chạy thu thập tin hôm nay từ các nguồn RSS đã bật. */
    @PostMapping("/admin/update-today")
    public String updateToday(RedirectAttributes redirect) {
        try {
            CollectionResult result = collectionService.collectToday();
            redirect.addFlashAttribute("lastResult", result);
            redirect.addFlashAttribute("message",
                "Đã cập nhật xong: thêm " + result.getTotalInserted() + " bài mới vào "
                + result.getDate());
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Lỗi khi cập nhật: " + e.getMessage());
        }
        return "redirect:/admin";
    }
}
