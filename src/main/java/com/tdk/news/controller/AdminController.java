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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {

    private final RssCollectionService collectionService;
    private final SourceRepository sourceRepository;
    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final CollectionLogRepository collectionLogRepository;
    private final DailyStatisticsRepository dailyStatisticsRepository;
    private final KeepAliveService keepAliveService;

    // Thread pool chay cac cong viec cap nhat nguon bat dong bo (cho phep nhieu nguon cung luc).
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);

    // Trang thai tung job cap nhat (key = "source-" + sourceId), de frontend poll.
    private final ConcurrentHashMap<String, CollectionJob> collectionJobs = new ConcurrentHashMap<>();
    private final AtomicInteger activeCollectionJobs = new AtomicInteger(0);

    /** Trang thai mot cong viec cap nhat tin cua mot nguon. */
    public static class CollectionJob {
        private String status;
        private Integer inserted;
        private Integer found;
        private String note;
        private String error;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;

        public CollectionJob(String status, Integer inserted, Integer found, String note, String error,
                             LocalDateTime startedAt, LocalDateTime finishedAt) {
            this.status = status;
            this.inserted = inserted;
            this.found = found;
            this.note = note;
            this.error = error;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getInserted() { return inserted; }
        public void setInserted(Integer inserted) { this.inserted = inserted; }
        public Integer getFound() { return found; }
        public void setFound(Integer found) { this.found = found; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    }

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

    /** Trang quản trị: danh sách nguồn và nút cập nhật tin hôm nay. */
    @GetMapping("/admin")
    public String admin(@RequestParam(required = false) String date, Model model) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));
        LocalDate selectedDate = (date == null || date.isBlank()) ? today : LocalDate.parse(date);
        model.addAttribute("pageTitle", "Quản trị");
        model.addAttribute("sources", sourceRepository.findAll());
        model.addAttribute("today", today);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("collectionLogs", collectionLogRepository.findRecent(50));

        Map<String, Integer> nameToId = categoryRepository.findAllNameToId();
        Map<Integer, String> categoryNames = new HashMap<>();
        nameToId.forEach((name, id) -> categoryNames.put(id, name));
        model.addAttribute("categoryNames", categoryNames);

        // Map id nguon -> ten nguon de hien thi trong bang thong ke
        Map<Integer, String> sourceNames = new HashMap<>();
        for (com.tdk.news.model.NewsSource s : sourceRepository.findAll()) {
            if (s.getId() != null) {
                sourceNames.put(s.getId(), s.getName());
            }
        }
        model.addAttribute("sourceNames", sourceNames);

        model.addAttribute("dailyStats", dailyStatisticsRepository.findByDate(selectedDate));
        model.addAttribute("keepAliveActive", keepAliveService.isKeepAliveActive());
        // lastResult được thêm bởi flash attribute sau khi POST; nếu chưa có thì để null.
        return "admin";
    }

    /** Chạy thu thập tin hôm nay từ các nguồn RSS đã bật (chạy đồng bộ). */
    @PostMapping("/admin/update-today")
    public String updateToday(RedirectAttributes redirect) {
        try {
            keepAliveService.activateKeepAlive();

            CollectionResult result = collectionService.collectToday();
            redirect.addFlashAttribute("lastResult", result);
            redirect.addFlashAttribute("message",
                "Đã cập nhật xong: thêm " + result.getTotalInserted() + " bài mới vào "
                + result.getDate());

            keepAliveService.deactivateKeepAlive();
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Lỗi khi cập nhật: " + e.getMessage());
            keepAliveService.deactivateKeepAlive();
        }
        return "redirect:/admin";
    }

    /** Bắt đầu cập nhật tin của 1 nguồn (bất đồng bộ): trả về ngay, không chờ hết xong. */
    @PostMapping("/admin/update-source/{id}")
    @ResponseBody
    public Map<String, Object> updateSource(@PathVariable Integer id) {
        String jobKey = "source-" + id;
        CollectionJob existing = collectionJobs.get(jobKey);
        if (existing != null && "running".equals(existing.getStatus())) {
            return Map.of("jobId", jobKey, "status", "running",
                    "message", "Nguồn đang được cập nhật, vui lòng đợi.");
        }

        keepAliveService.activateKeepAlive();
        activeCollectionJobs.incrementAndGet();

        LocalDateTime startedAt = now();
        CollectionJob job = new CollectionJob("running", null, null, null, null, startedAt, null);
        collectionJobs.put(jobKey, job);

        CompletableFuture.runAsync(() -> {
            try {
                RssCollectionService.SourceResult result = collectionService.collectSingleSource(id);
                collectionJobs.put(jobKey, new CollectionJob("done", result.getInserted(), result.getFound(),
                        result.getNote(), null, job.getStartedAt(), now()));
            } catch (Exception e) {
                collectionJobs.put(jobKey, new CollectionJob("error", null, null, null, e.getMessage(),
                        job.getStartedAt(), now()));
            } finally {
                if (activeCollectionJobs.decrementAndGet() <= 0) {
                    keepAliveService.deactivateKeepAlive();
                }
            }
        }, asyncExecutor);

        return Map.of("jobId", jobKey, "status", "started");
    }

    /** Tra cứu trạng thái công việc cập nhật của 1 nguồn (dùng poll từ frontend). */
    @GetMapping("/admin/update-source/{id}/status")
    @ResponseBody
    public CollectionJob sourceStatus(@PathVariable Integer id) {
        CollectionJob job = collectionJobs.get("source-" + id);
        if (job == null) {
            return new CollectionJob("idle", null, null, null, null, null, null);
        }
        return job;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Saigon"));
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
