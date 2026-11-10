# Hướng dẫn triển khai Spring Boot lên Render.com

Tài liệu hướng dẫn triển khai ứng dụng Spring Boot (`web/news`) lên Render.com cùng với cơ sở dữ liệu PostgreSQL trên Render.

---

## 1. Yêu cầu trước khi triển khai

- Tài khoản [Render.com](https://render.com) (miễn phí có sẵn).
- Mã nguồn đã được đẩy lên GitHub/GitLab/Bitbucket.
- PostgreSQL đã được tạo trên Render (hoặc dùng Render PostgreSQL).
- Java 11 (theo `pom.xml`).
- Maven đã cài đặt (dùng để build local nếu cần).

---

## 2. Chuẩn bị mã nguồn

### 2.1 Kiểm tra file cấu hình quan trọng

| File | Mục đích |
|------|----------|
| `pom.xml` | Quản lý dependency Java/Maven |
| `src/main/resources/application.properties` | Cấu hình kết nối DB, port server |
| `database/add-unique-url.sql` | Migration thêm UNIQUE constraint cho cột `Url` |
| `database/add-is-read.sql` | Migration thêm cột `IsRead` |
| `Procfile` (nếu có) | Lệnh khởi động trên Render |

### 2.2 Tạo file `Procfile` (nếu chưa có)

Tạo file `Procfile` tại thư mục gốc project `web/news/`:

```
web: java -Dserver.port=$PORT -jar target/news-0.0.1-SNAPSHOT.jar
```

Render sẽ dùng file này để biết cách khởi động ứng dụng.

---

## 3. Tạo PostgreSQL trên Render

1. Đăng nhập [Render Dashboard](https://dashboard.render.com).
2. Nhấn **New +** → **PostgreSQL**.
3. Điền thông tin:
   - **Name**: `vietnamnews-db` (hoặc tên tuỳ ý).
   - **Database**: `news`.
   - **User**: `postgres` (hoặc tên user tuỳ ý).
   - **Region**: Chọn khu vực gần bạn nhất.
4. Nhấn **Create Database**.
5. Sau khi tạo xong, Render sẽ hiển thị **Internal Database URL** (ví dụ: `postgresql://postgres:xxx@dpg-xxx.oregon-postgres.render.com/news`). Lưu lại để dùng trong bước cấu hình biến môi trường.

---

## 4. Tạo Web Service trên Render

1. Nhấn **New +** → **Web Service**.
2. Kết nối repository GitHub chứa mã nguồn.
3. Chọn repository nhánh chứa thư mục `web/news/`.
4. Cấu hình:
   - **Name**: `vietnamnews-web`
   - **Environment**: `Java`
   - **Region**: Giống với PostgreSQL để giảm độ trễ.
   - **Branch**: `main` (hoặc nhánh bạn dùng).
   - **Root Directory**: `web/news` (quan trọng: vì file `pom.xml` nằm trong thư mục này).
   - **Build Command**: `mvn clean package -DskipTests -q`
   - **Start Command**: `java -Dserver.port=$PORT -jar target/news-0.0.1-SNAPSHOT.jar`

---

## 5. Cấu hình biến môi trường (Environment Variables)

Trên trang **Environment** của Web Service, thêm các biến sau:

| Biến | Giá trị ví dụ | Ghi chú |
|------|--------------|---------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://dpg-xxx.oregon-postgres.render.com:5432/news?sslmode=require` | Lấy từ Internal Database URL của PostgreSQL Render |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | User DB |
| `SPRING_DATASOURCE_PASSWORD` | `xxx` | Mật khẩu DB |
| `SERVER_PORT` | `10000` | Render tự cung cấp `$PORT`, có thể để trống |
| `SPRING_MVC_SERVLET_PATH` | `/` | Giữ nguyên |
| `LOGGING_LEVEL_COM_TKD_NEWS` | `DEBUG` | Tùy chọn |

### Cách lấy Internal Database URL

Vào **Render Dashboard → PostgreSQL → your-db → Connect** → copy **Internal Database URL**. Đổi prefix từ `postgresql://` sang `jdbc:postgresql://` và thêm `?sslmode=require` vào cuối.

Ví dụ:
```
Internal Database URL: postgresql://postgres:abcd1234@dpg-xxx.oregon-postgres.render.com/news
SPRING_DATASOURCE_URL: jdbc:postgresql://dpg-xxx.oregon-postgres.render.com:5432/news?sslmode=require
```

---

## 6. Chạy migration SQL trên PostgreSQL Render

Trước khi khởi động ứng dụng, cần chạy các file migration để tạo UNIQUE constraint và cột `IsRead`.

### Cách 1: Dùng Render Shell (khuyên dùng)

1. Vào **Render Dashboard → PostgreSQL → your-db**.
2. Nhấn **Connect** → **PSQL** (hoặc dùng **Shell** nếu có).
3. Chạy lần lượt:

```sql
-- Thêm UNIQUE constraint cho cột Url
CREATE UNIQUE INDEX IF NOT EXISTS ix_articles_url_unique ON "Articles" ("Url");

-- Thêm cột IsRead
ALTER TABLE "Articles" ADD COLUMN IF NOT EXISTS "IsRead" boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS ix_articles_isread ON "Articles" ("IsRead");
```

### Cách 2: Dùng psql trên máy local

```bash
set PGPASSWORD=mat-khau-db
psql "host=dpg-xxx.oregon-postgres.render.com port=5432 dbname=news user=postgres sslmode=require" -f database/add-unique-url.sql
psql "host=dpg-xxx.oregon-postgres.render.com port=5432 dbname=news user=postgres sslmode=require" -f database/add-is-read.sql
```

---

## 7. Build và triển khai

1. Commit và push mã nguồn lên GitHub.
2. Render sẽ tự động detect thay đổi và tiến hành build/deploy.
3. Theo dõi log trên **Render Dashboard → Web Service → Logs**.
4. Khi build xong, ứng dụng sẽ chạy tại URL Render cung cấp (ví dụ: `https://vietnamnews-web.onrender.com`).

---

## 8. Kiểm tra sau khi triển khai

Truy cập các endpoint sau để xác nhận hoạt động正常:

| URL | Mục đích |
|-----|----------|
| `https://<app>.onrender.com/news` | Xem danh sách tin hôm nay |
| `https://<app>.onrender.com/news?date=2026-11-10&categoryId=3` | Lọc tin theo ngày + chuyên mục |
| `https://<app>.onrender.com/news/3536` | Xem chi tiết 1 bài viết |
| `https://<app>.onrender.com/admin` | Trang quản trị (nếu có) |

---

## 9. Các vấn đề thường gặp

### Lỗi 500: `there is no unique or exclusion constraint matching the ON CONFLICT specification`

**Nguyên nhân:** Chưa chạy migration `add-unique-url.sql` trên PostgreSQL Render.

**Khắc phục:** Chạy lệnh SQL sau trên DB Render:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS ix_articles_url_unique ON "Articles" ("Url");
```

### Lỗi kết nối DB: `Connection refused`

**Nguyên nhân:** `SPRING_DATASOURCE_URL` sai hoặc không thêm `sslmode=require`.

**Khắc phục:** Kiểm tra lại biến môi trường, đảm bảo URL bắt đầu bằng `jdbc:postgresql://` và có `?sslmode=require` ở cuối.

### Lỗi build: Maven không tìm thấy dependency

**Nguyên nhân:** Root Directory không đúng hoặc `pom.xml` không nằm ở root được chọn.

**Khắc phục:** Trong Render Web Service, set **Root Directory** = `web/news`.

### Trên Android hiển thị `localhost` không được

**Nguyên nhân:** `localhost` trên điện thoại trỏ đến chính điện thoại, không phải máy chủ Render.

**Khắc phục:** Luôn dùng domain Render (`https://<app>.onrender.com`) thay vì `localhost`.

---

## 10. Cấu trúc thư mục tham khảo

```
web/news/
├── pom.xml
├── Procfile
├── src/
│   ├── main/
│   │   ├── java/com/tdk/news/...
│   │   └── resources/
│   │       ├── application.properties
│   │       └── templates/
│   └── ...
├── database/
│   ├── add-unique-url.sql
│   └── add-is-read.sql
└── docs/
    └── render.md
```

---

## 11. Liên hệ

Nếu cần hỗ trợ thêm, kiểm tra log trên Render Dashboard hoặc mở issue trên repository GitHub.
