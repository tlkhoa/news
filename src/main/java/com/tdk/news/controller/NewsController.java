package com.tdk.news.controller;

import com.tdk.news.model.ArticleSummary;
import com.tdk.news.model.NewsArticle;
import com.tdk.news.repository.ArticleRepository;
import com.tdk.news.repository.CategoryRepository;
import com.tdk.news.service.ContentFetchService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class NewsController {

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final ContentFetchService contentFetchService;

    public NewsController(ArticleRepository articleRepository,
                          CategoryRepository categoryRepository,
                          ContentFetchService contentFetchService) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.contentFetchService = contentFetchService;
    }

    /** Trang chủ: chuyển hướng đến tin hôm nay. */
    @GetMapping("/")
    public String home() {
        return "redirect:/news?date=" + LocalDate.now(ZoneId.of("Asia/Saigon"));
    }

    /** Danh sách tin tức theo ngày (tùy chọn lọc chuyên mục). */
    @GetMapping("/news")
    public String listByDate(@RequestParam(required = false) String date,
                             @RequestParam(required = false) Integer categoryId,
                             Model model) {
        LocalDate selected = (date == null || date.isBlank())
            ? LocalDate.now(ZoneId.of("Asia/Saigon"))
            : LocalDate.parse(date);

        List<ArticleSummary> articles;
        int total;
        if (categoryId != null) {
            articles = articleRepository.findByDateAndCategory(selected, categoryId);
            total = articleRepository.countByDateAndCategory(selected, categoryId);
        } else {
            articles = articleRepository.findByDate(selected);
            total = articleRepository.countByDate(selected);
        }

        List<Object[]> categories = categoryRepository.findAllCategories();

        model.addAttribute("pageTitle", "Tin tức");
        model.addAttribute("date", selected);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("categories", categories);
        model.addAttribute("articles", articles);
        model.addAttribute("total", total);
        model.addAttribute("today", LocalDate.now(ZoneId.of("Asia/Saigon")));
        return "news";
    }

    /** Trang đọc chi tiết 1 bài viết (responsive, dễ đọc trên điện thoại). */
    @GetMapping("/news/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        Optional<NewsArticle> articleOpt = articleRepository.findById(id);
        if (articleOpt.isEmpty()) {
            redirect.addFlashAttribute("error", "Không tìm thấy bài viết.");
            return "redirect:/news";
        }

        NewsArticle article = articleOpt.get();

        // Đánh dấu bài đã đọc để danh sách hiển thị màu khác.
        articleRepository.markAsRead(id);

        // Lazy-load hybrid: nếu DB chưa có toàn văn → fetch từ Url, lưu DB, rồi hiển thị.
        String content = article.getContent();
        if ((content == null || content.isBlank()) && article.getUrl() != null) {
            String fetched = contentFetchService.fetchContent(id);
            if (fetched != null && !fetched.isBlank()) {
                article.setContent(fetched);
                content = fetched;
            }
        }

        // Chuẩn hóa nội dung hiển thị: xoá đoạn quảng cáo/kết bài (từ ảnh mediacdn.vn
        // đầu tiên đến hết một trong các câu kết "Chúc bạn đọc báo vui vẻ!" /
        // "trên Google tìm kiếm. Xem hướng dẫn") nếu có, áp dụng cả nội dung đã lưu sẵn trong DB.
        content = contentFetchService.cleanForDisplay(content);
        article.setContent(content);

        model.addAttribute("pageTitle", article.getTitle());
        model.addAttribute("article", article);
        model.addAttribute("today", LocalDate.now(ZoneId.of("Asia/Saigon")));
        // Giữ lại bộ lọc chuyên mục / ngày khi quay lại danh sách
        if (article.getCategoryId() != null) {
            model.addAttribute("categoryId", article.getCategoryId());
        }
        model.addAttribute("date", article.getPublishedAt() != null
                ? article.getPublishedAt().toLocalDate()
                : LocalDate.now(ZoneId.of("Asia/Saigon")));
        return "detail";
    }

    /** Đánh dấu bài viết đã đọc (gọi AJAX từ danh sách khi click vào bài). */
    @PostMapping("/news/{id}/read")
    @ResponseBody
    public Map<String, Object> markAsReadAjax(@PathVariable Long id) {
        articleRepository.markAsRead(id);
        return Map.of("ok", true, "id", id);
    }

    /**
     * Lấy nội dung bài viết để đọc aloud (TTS).
     * Nếu DB chưa có toàn văn → fetch từ Url, lưu DB, rồi trả về.
     * Trả về JSON: { id, title, content } (content là văn bản thô, đã loại thẻ HTML).
     */
    @GetMapping("/news/{id}/content")
    @ResponseBody
    public Map<String, Object> getArticleContent(@PathVariable Long id) {
        System.out.println("[ListTTS] GET /news/" + id + "/content called");
        Optional<NewsArticle> articleOpt = articleRepository.findById(id);
        if (articleOpt.isEmpty()) {
            System.out.println("[ListTTS] article not found id=" + id);
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Không tìm thấy bài viết.");
            err.put("id", id);
            return err;
        }

        NewsArticle article = articleOpt.get();
        System.out.println("[ListTTS] found article id=" + id + " title=" + article.getTitle()
            + " contentBlank=" + (article.getContent() == null || article.getContent().isBlank())
            + " fetchedAt=" + article.getContentFetchedAt());

        String content = article.getContent();
        LocalDateTime fetchedAt = article.getContentFetchedAt();

        if ((content == null || content.isBlank() || fetchedAt == null) && article.getUrl() != null) {
            System.out.println("[ListTTS] fetching content from URL: " + article.getUrl());
            String fetched = contentFetchService.fetchContent(id);
            if (fetched != null && !fetched.isBlank()) {
                content = fetched;
                article.setContent(content);
                System.out.println("[ListTTS] fetched content length=" + fetched.length());
            } else {
                System.out.println("[ListTTS] fetch returned null/blank");
            }
        }

        String plainText = stripHtml(content == null ? "" : content);
        System.out.println("[ListTTS] plainText length=" + plainText.length());
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("title", article.getTitle());
        result.put("content", plainText);
        return result;
    }

    /** Loại bỏ thẻ HTML và chuỗi khoảng trắng dư để TTS đọc văn bản thuần. */
    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    /** Thủ công tải lại toàn văn cho bài viết (dùng khi nút "Tải toàn văn" được nhấn). */
    @PostMapping("/news/{id}/fetch-content")
    public String fetchContent(@PathVariable Long id, RedirectAttributes redirect) {
        String fetched = contentFetchService.fetchContent(id);
        if (fetched != null && !fetched.isBlank()) {
            redirect.addFlashAttribute("message", "Đã tải toàn văn thành công.");
        } else {
            redirect.addFlashAttribute("error", "Không lấy được nội dung từ website gốc.");
        }
        return "redirect:/news/" + id;
    }
}
