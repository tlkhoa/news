-- Them cot SourceId vao bang DailyStatistics de tung nguon luu thong ke hang doc lap
-- va sua rang buoc UNIQUE thanh ("Date", "CategoryId", "SourceId").
-- Chay file nay mot lan tren database.

-- 1. Them cot SourceId (neu chua co)
ALTER TABLE "DailyStatistics" ADD COLUMN IF NOT EXISTS "SourceId" INTEGER;

-- 2. Xoa constraint UNIQUE cu ("Date", "CategoryId") neu ton tai
-- Ten constraint mac dinh cu Postgres la <table>_<col>_key; neu ten khac hay chinh sua ben duoi.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = '"DailyStatistics"'::regclass
          AND contype = 'u'
          AND pg_get_constraintdef(oid) LIKE '%"Date"%'
          AND pg_get_constraintdef(oid) LIKE '%"CategoryId"%'
    ) THEN
        -- Lay ten constraint thuc te de xoa
        EXECUTE (
            SELECT 'ALTER TABLE "DailyStatistics" DROP CONSTRAINT "' || conname || '"'
            FROM pg_constraint
            WHERE conrelid = '"DailyStatistics"'::regclass
              AND contype = 'u'
              AND pg_get_constraintdef(oid) LIKE '%"Date"%'
              AND pg_get_constraintdef(oid) LIKE '%"CategoryId"%'
            LIMIT 1
        );
    END IF;
END $$;

-- 3. Them constraint UNIQUE moi ("Date", "CategoryId", "SourceId")
ALTER TABLE "DailyStatistics"
    ADD CONSTRAINT "daily_statistics_date_category_source_uniq"
    UNIQUE ("Date", "CategoryId", "SourceId");
