-- Thêm ràng buộc UNIQUE cho cột "Articles"."Url" để hỗ trợ ON CONFLICT ("Url") DO NOTHING.
-- Chạy lệnh này trên PostgreSQL trước khi khởi động ứng dụng Spring Boot (hoặc sau khi
-- bảng đã được .NET EF Core tạo ra).
--
-- Lưu ý: nếu đã có dữ liệu trùng lặp trong cột "Url", lệnh CREATE UNIQUE INDEX sẽ thất bại.
-- Trong trường hợp đó, cần dọn dữ liệu trùng trước:
--   DELETE FROM "Articles" a1 USING "Articles" a2
--   WHERE a1."Id" < a2."Id" AND a1."Url" = a2."Url";

CREATE UNIQUE INDEX IF NOT EXISTS ix_articles_url_unique ON "Articles" ("Url");
