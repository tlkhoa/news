(function () {
    'use strict';

    // Khi nhấn Enter trên ô nhập ngày (nếu có) thì không submit form bất ngờ
    var dateInputs = document.querySelectorAll('input[type="date"]');
    dateInputs.forEach(function (input) {
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                var form = input.closest('form');
                if (form) form.requestSubmit ? form.requestSubmit() : form.submit();
            }
        });
    });

    // Tự đặt giá trị mặc định là hôm nay cho ô ngày nếu đang trống
    document.addEventListener('DOMContentLoaded', function () {
        var today = new Date().toISOString().split('T')[0];
        dateInputs.forEach(function (input) {
            if (!input.value) input.value = today;
        });
    });

    // Khi click vào bài viết trong danh sách: đánh dấu đã đọc ngay (AJAX, không chặn điều hướng)
    // và tô màu item đó để người dùng biết đã đọc. Đồng thời lưu id bài vừa đọc để
    // khi quay lại danh sách có thể cuộn đến đúng item này.
    document.addEventListener('click', function (e) {
        var card = e.target.closest('.article-card');
        if (!card) return;
        var href = card.getAttribute('href');
        if (!href) return;
        var id = card.getAttribute('data-id');
        if (!id) {
            var idMatch = href.match(/\/news\/(\d+)(\?|$)/);
            if (!idMatch) return;
            id = idMatch[1];
        }

        // Gửi yêu cầu đánh dấu đã đọc lên server. keepalive giúp yêu cầu vẫn được gửi
        // khi trình duyệt đang rời trang để mở chi tiết bài viết.
        fetch('/news/' + id + '/read', { method: 'POST', keepalive: true })
            .catch(function () {
                // Bỏ qua lỗi mạng; trạng thái sẽ được đồng bộ lại khi tải lại danh sách.
            });

        // Lưu id bài vừa đọc vào sessionStorage để khôi phục vị trí cuộn khi quay lại.
        sessionStorage.setItem('news_last_read_id', id);

        // Phản hồi trực quan ngay lập tức trên item vừa click.
        markCardAsRead(card);
    });

    // Đánh dấu một item là đã đọc: thêm class màu + chèn nhãn "Đã đọc" nếu chưa có.
    function markCardAsRead(card) {
        if (!card) return;
        card.classList.add('is-read');
        ensureReadBadge(card);
    }

    // Chèn nhãn "Đã đọc" vào footer item nếu chưa có.
    function ensureReadBadge(card) {
        if (!card) return;
        var footer = card.querySelector('.article-card__footer');
        if (!footer) return;
        if (footer.querySelector('.is-read-badge')) return;

        var badge = document.createElement('span');
        badge.className = 'badge is-read-badge';
        badge.textContent = 'Đã đọc';

        var firstBadge = footer.querySelector('.badge');
        if (firstBadge) {
            firstBadge.after(badge);
        } else {
            footer.appendChild(badge);
        }
    }

    // Cuộn đến item vừa đọc (nếu có) khi trang danh sách hiển thị.
    // Hàm này được gọi cả khi load thường và khi khôi phục từ back-forward cache (nhấn Back).
    function scrollToLastRead() {
        var grid = document.querySelector('.articles-grid');
        if (!grid) return;

        var lastReadId = sessionStorage.getItem('news_last_read_id');
        if (!lastReadId) return;

        var card = grid.querySelector('.article-card[data-id="' + lastReadId + '"]');
        if (card) {
            card.scrollIntoView({ behavior: 'auto', block: 'center' });
            // Đảm bảo nhãn "Đã đọc" xuất hiện (quy yếu khi khôi phục từ back-forward cache).
            markCardAsRead(card);
        }
        sessionStorage.removeItem('news_last_read_id');
    }

    // Khi tải trang danh sách thường.
    document.addEventListener('DOMContentLoaded', function () {
        var today = new Date().toISOString().split('T')[0];
        dateInputs.forEach(function (input) {
            if (!input.value) input.value = today;
        });
        scrollToLastRead();
    });

    // Khi nhấn Back/Forward: trình duyệt có thể khôi phục trang từ back-forward cache,
    // lúc đó DOMContentLoaded không chạy lại → dùng sự kiện pageshow.
    window.addEventListener('pageshow', function (e) {
        if (e.persisted) {
            scrollToLastRead();
        }
    });

    // Trang chi tiết: không hiển thị ảnh đại diện (.detail-image) ở trên cùng,
    // chỉ giữ các ảnh bên trong nội dung bài viết (.detail-content).
    document.addEventListener('DOMContentLoaded', function () {
        var detailImage = document.querySelector('.detail-image');
        if (detailImage) {
            detailImage.style.display = 'none';
        }
    });
})();
