package com.tdk.news.service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Chuẩn hóa và so sánh văn bản tiếng Việt: NFC, chữ thường, bỏ dấu (kể cả đ→d),
 * bỏ dấu câu, tách token, loại stopword. Port từ C# VietnameseText.
 */
public class VietnameseTextUtils {

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
        "va","cua","la","mot","nhung","cac","trong","voi","duoc","cho","theo","sau","truoc",
        "khi","nay","do","da","se","dang","co","khong","de","den","tu","boi","ve","o","ra","len","xuong",
        "di","lai","the","nhu","hon","nhat","rat","cung","chi","neu","ma","hoac","hay",
        "vi","nen","tuy","tung","moi","nhieu","it","chung","rieng",
        "thang","ngay","gio","phut","hom","sang","chieu","khuya","vua","hien","ay","kia",
        "tren","duoi","giua","ben","canh","ngoai","trong","vao",
        "ong","ba","em","co","ong ba","nguoi","toi","chung toi","ho","no","minh",
        "nham","muc","sao","nhe","a","u","o","e","i","gi","nao","the nao","ra sao","khi nao"
    ));

    /**
     * Chuẩn hóa văn bản để so sánh: NFC, chữ thường, bỏ dấu, thay ký tự không phải chữ/số bằng cách xách.
     * Port từ C# VietnameseText.NormalizeForComparison.
     */
    public static String normalizeForComparison(String text) {
        if (text == null || text.isBlank()) return "";

        // Normalize FormD để tách dấu thanh khỏi ký tự cơ bản.
        String formD = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(formD.length());
        for (int i = 0; i < formD.length(); i++) {
            char ch = formD.charAt(i);
            // Bỏ các dấu kết hợp (NonSpacingMark).
            if (Character.getType(ch) == Character.NON_SPACING_MARK) continue;
            // 'đ'/'Đ' không tách được bằng FormD — xử lý riêng.
            if (ch == 'đ' || ch == 'Đ') { sb.append('d'); continue; }
            sb.append(ch);
        }

        String lowered = sb.toString().toLowerCase(Locale.ROOT);
        // Normalize FormC để ghép lại các ký tự cơ bản.
        lowered = Normalizer.normalize(lowered, Normalizer.Form.NFC);

        // Thay mọi ký tự không phải chữ/số bằng cách xách.
        StringBuilder cleaned = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (Character.isLetterOrDigit(ch)) cleaned.append(ch);
            else cleaned.append(' ');
        }

        // Ghép lại, xóa khoảng trắng thừa.
        return Arrays.stream(cleaned.toString().trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));
    }

    /**
     * Token đã chuẩn hóa, đã loại stopword và token quá ngắn.
     */
    public static List<String> contentTokens(String text) {
        String norm = normalizeForComparison(text);
        List<String> tokens = new ArrayList<>();
        for (String t : norm.split("\\s+")) {
            if (t.length() > 1 && !STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    public static Set<String> tokenSet(String text) {
        return new HashSet<>(contentTokens(text));
    }

    /** Jaccard similarity: |∩| / |∪|. */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (String t : small) if (large.contains(t)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    /** Overlap coefficient: |∩| / min(|a|, |b|). */
    public static double overlapCoefficient(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (String t : small) if (large.contains(t)) intersection++;
        return (double) intersection / small.size();
    }

    /** Số token giao nhau. */
    public static int intersectionCount(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int count = 0;
        for (String t : small) if (large.contains(t)) count++;
        return count;
    }

    /** Chữ ký token: distinct, sắp xếp, giới hạn số token. */
    public static String buildSignature(Collection<String> tokens, int maxTokens) {
        List<String> sorted = new ArrayList<>(new HashSet<>(tokens));
        Collections.sort(sorted);
        if (sorted.size() > maxTokens) sorted = sorted.subList(0, maxTokens);
        return String.join(" ", sorted);
    }
}
