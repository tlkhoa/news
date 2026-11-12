package com.tdk.news.controller;

import com.tdk.news.repository.ArticleRepository;
import com.tdk.news.repository.CollectionLogRepository;
import com.tdk.news.repository.CategoryRepository;
import com.tdk.news.repository.DailyStatisticsRepository;
import com.tdk.news.repository.SourceRepository;
import com.tdk.news.service.KeepAliveService;
import com.tdk.news.service.RssCollectionService;
import com.tdk.news.service.RssCollectionService.CollectionResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;

@Controller
public class AdminController {

    private final RssCollectionService collectionService;
    private final SourceRepository sourceRepository;
    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final CollectionLogRepository collectionLogRepository;
    private final DailyStatisticsRepository dailyStatisticsRepository;
    private final KeepAliveService keepAliveService;

    public AdminController(RssCollectionService collectionService, SourceRepository sourceRepository,
                           CategoryRepository categoryRepository,
                           ArticleRepository articleRepository,
                           CollectionLogRepository collectionLogRepository,
                           DailyStatisticsRepository dailyStatisticsRepository,
                           KeepAliveService keepAliveService) {
        this.collectionService = collectionService;
        this.sourceRepository = sourceRepository;
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
        this.collectionLogRepository = collectionLogRepository;
        this.dailyStatisticsRepository = dailyStatisticsRepository;
        this.keepAliveService = keepAliveService;
    }

    /** Trang quản trị: danh sách nguồn, nút cập nhật tin hôm nay, nhật ký thu thuat va thong ke. */
    @GetMapping("/admin")
    public String admin(Model model, HttpSession session) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));
        model.addAttribute("pageTitle", "Quản trị");
        model.addAttribute("sources", sourceRepository.findAll());
        model.addAttribute("today", today);
        model.addAttribute("collectionLogs", collectionLogRepository.findRecent(50));

        Map<String, Integer> nameToId = categoryRepository.findAllNameToId();
        Map<Integer, String> categoryNames = new HashMap<>();
        nameToId.forEach((name, id) -> categoryNames.put(id, name));
        model.addAttribute("categoryNames", categoryNames);

        model.addAttribute("dailyStats", dailyStatisticsRepository.findByDate(today));
        model.addAttribute("keepAliveActive", keepAliveService.isKeepAliveActive());

        // Đọc kết quả cập nhật chạy nền từ session (nếu có) và xóa để chỉ hiện 1 lần.
        CollectionResult lastResult = (CollectionResult) session.getAttribute("lastResult");
        String lastResultError = (String) session.getAttribute("lastResultError");
        if (lastResult != null) {
            model.addAttribute("lastResult", lastResult);
            session.removeAttribute("lastResult");
        }
        if (lastResultError != null) {
            model.addAttribute("error", "Lỗi khi cập nhật: " + lastResultError);
            session.removeAttribute("lastResultError");
        }
        // lastResult cũng có thể đến từ flash attribute (nếu chưa chuyển hoàn toàn sang async).
        return "admin";
    }

    /** Chức năng cập nhật hiện chỉ chạy qua Background Worker mỗi 6 giờ. */
    @PostMapping("/admin/update-today")
    public String updateToday(RedirectAttributes redirect) {
        redirect.addFlashAttribute("error",
            "Chức năng cập nhật hiện chỉ chạy tự động qua Background Worker mỗi 6 giờ.");
        return "redirect:/admin";
    }

    /** Xóa toàn bộ tin tức, nhật ký thu thuat va thong ke cua ngay hom nay (giong chuc nang xoa tin hom nay o C#). */
    @PostMapping("/admin/delete-today")
    public String deleteToday(RedirectAttributes redirect) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));
        try {
            int deletedArticles = articleRepository.deleteByDate(today);
            int deletedLogs = collectionLogRepository.deleteByDate(today);
            int deletedStats = dailyStatisticsRepository.deleteByDate(today);
            redirect.addFlashAttribute("message",
                "Đã xóa " + deletedArticles + " bài tin, " + deletedLogs
                + " nhật ký thu thập và " + deletedStats + " thống kê ngày "
                + today);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Lỗi khi xóa tin hôm nay: " + e.getMessage());
        }
        return "redirect:/admin";
    }
}

