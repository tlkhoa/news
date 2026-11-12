-- Thêm 2 bảng CollectionLogs va DailyStatistics (port tu C#)
-- dung cho chuc nang admin: nhat ky thu thuat va thong ke theo ngay.
-- Chay lenh nay tren PostgreSQL truoc khi khoi dong ung dung Spring Boot.

-- Bang nhat ky lan thu thuat (giong CollectionLog o C# NewsCollectorService).
CREATE TABLE IF NOT EXISTS "CollectionLogs" (
    "Id" bigserial PRIMARY KEY,
    "SourceId" integer NOT NULL,
    "SourceName" varchar(120) NOT NULL DEFAULT '',
    "StartedAt" timestamp NOT NULL,
    "FinishedAt" timestamp NOT NULL,
    "ItemsFound" integer NOT NULL DEFAULT 0,
    "ItemsInserted" integer NOT NULL DEFAULT 0,
    "CollectionMethod" varchar(40) NOT NULL DEFAULT '',
    "Note" varchar(2000),
    "Error" varchar(2000)
);
CREATE INDEX IF NOT EXISTS "IX_CollectionLogs_SourceId" ON "CollectionLogs" ("SourceId");
CREATE INDEX IF NOT EXISTS "IX_CollectionLogs_StartedAt" ON "CollectionLogs" ("StartedAt" DESC);

-- Bang thong ke theo ngay (giong DailyStatistics o C#).
-- (Date, CategoryId) la duy nhat: CategoryId NULL la tong chung ca trang,
-- CategoryId co gia tri la thong ke theo tung chuyen muc.
CREATE TABLE IF NOT EXISTS "DailyStatistics" (
    "Id" bigserial PRIMARY KEY,
    "Date" date NOT NULL,
    "CategoryId" integer,
    "ArticleCount" integer NOT NULL DEFAULT 0,
    "AveragePopularity" double precision NOT NULL DEFAULT 0,
    CONSTRAINT "UQ_DailyStatistics_Date_CategoryId" UNIQUE ("Date", "CategoryId")
);
CREATE INDEX IF NOT EXISTS "IX_DailyStatistics_Date" ON "DailyStatistics" ("Date");
