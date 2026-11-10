package com.tdk.news.service;

import com.tdk.news.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phân loại lĩnh vực theo từ khóa, KHÔNG PHỤ THUỘC DẤU: cả văn bản và luật đều được
 * chuẩn hóa về không dấu trước khi khớp. Danh mục được nạp cache một lần thay vì
 * truy vấn DB cho từng bài viết. Port từ C# NewsClassifier.
 */
@Service
public class NewsClassifierService {

    private static final Map<String, String[]> RULES = new LinkedHashMap<>() {{
        put("Chính trị – Xã hội", new String[]{"chinh phu","quoc hoi","xa hoi","dia phuong","chinh sach","nguoi dan","thu tuong","bo truong","uy ban","khoa hop","nghi quyet","thong tu","nghi dinh"});
        put("Kinh tế – Tài chính", new String[]{"kinh te","doanh nghiep","ngan hang","chung khoan","lai suat","tai chinh","gia vang","ty gia","xuat khau","dau tu","co phieu","trai phieu","loi nhuan","doanh thu","thue"});
        put("Công nghệ – AI", new String[]{"cong nghe","tri tue nhan tao","ai ","chip","phan mem","dien thoai","internet","smartphone","ung dung","du lieu","an ninh mang","ban dan"});
        put("Khoa học", new String[]{"khoa hoc","nghien cuu","vu tru","phat minh","sinh hoc","vat ly","hoa hoc","di truyen"});
        put("Giáo dục", new String[]{"giao duc","hoc sinh","sinh vien","dai hoc","thi tot nghiep","truong hoc","ky thi","diem thi","tuyen sinh","gia vien","bo giao duc"});
        put("Sức khỏe – Y tế", new String[]{"suc khoe","benh vien","bac si","dich benh","y te","vac xin","ca benh","bo y te","benh nhan","kham benh","duoc pham"});
        put("Pháp luật", new String[]{"phap luat","cong an","toa an","khoi to","xet xu","dieu tra","bat giu","vu an","bi can","luat su","vien kiem sat"});
        put("Thế giới", new String[]{"the gioi","quoc te","hoa ky","trung quoc","nga","chau au","my ","han quoc","nhat ban","tong thong","lien hop quoc"});
        put("Bất động sản", new String[]{"bat dong san","chung cu","dat nen","nha o","thi truong nha","can ho","quy hoach","du an nha"});
        put("Tai nạn – An toàn", new String[]{"tai nan","chay no","sat lo","cuu ho","mat an toan","lu lut","con bao","bao so","chay nha","hoa hoan","sap do"});
        put("Thể thao", new String[]{"bong da","the thao","world cup","olympic","huan luyen vien","ngoai hang anh","v league","doi tuyen","cup","giai dau"});
        put("Giải trí", new String[]{"giai tri","nghe si","ca si","phim anh","am nhac","showbiz","dien vien","concert","mv ","album"});
        put("Du lịch – Đời sống", new String[]{"du lich","am thuc","doi song","kham pha","khach san","resort","diem den"});
        put("Ô tô – Xe máy", new String[]{"o to","xe may","xe dien","giao thong","mau xe","vinfast","tay lai"});
    }};

    private static final Map<String, String[]> NORMALIZED_RULES;
    static {
        NORMALIZED_RULES = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : RULES.entrySet()) {
            String[] normalized = Arrays.stream(entry.getValue())
                    .map(VietnameseTextUtils::normalizeForComparison)
                    .map(String::trim)
                    .toArray(String[]::new);
            NORMALIZED_RULES.put(entry.getKey(), normalized);
        }
    }

    private final CategoryRepository categoryRepository;
    private Map<String, Integer> categoryIds; // cache

    public NewsClassifierService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Phân loại bài viết theo tiêu đề + mô tả. Trả về CategoryId hoặc null nếu không khớp.
     * Port từ C# NewsClassifier.ClassifyAsync.
     */
    public Integer classify(String title, String description) {
        String text = VietnameseTextUtils.normalizeForComparison(title + " " + description);
        String padded = " " + text + " "; // Khớp theo ranh giới từ/cụm từ, tránh "ai" khớp trong "mai".

        String bestCategory = null;
        int bestScore = 0;
        for (Map.Entry<String, String[]> entry : NORMALIZED_RULES.entrySet()) {
            String category = entry.getKey();
            String[] keywords = entry.getValue();
            int score = 0;
            for (String k : keywords) {
                // Cộng điểm theo độ dài cụm từ: cụm dài (đặc trưng) đáng giá hơn từ đơn.
                if (padded.contains(" " + k + " ")) score += Math.max(1, k.length() / 4);
            }
            if (score > bestScore) {
                bestScore = score;
                bestCategory = category;
            }
        }
        if (bestCategory == null) return null;

        Map<String, Integer> ids = getCategoryIds();
        return ids.get(bestCategory);
    }

    private Map<String, Integer> getCategoryIds() {
        if (categoryIds != null) return categoryIds;
        categoryIds = categoryRepository.findAllNameToId();
        return categoryIds;
    }

    public void clearCache() {
        categoryIds = null;
    }
}
