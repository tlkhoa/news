-- Thêm cột IsRead để đánh dấu bài viết đã đọc (dùng cho danh sách tin).
-- Chạy lệnh này trên PostgreSQL trước khi khởi động ứng dụng Spring Boot.
ALTER TABLE "Articles" ADD COLUMN IF NOT EXISTS "IsRead" boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS ix_articles_isread ON "Articles" ("IsRead");
