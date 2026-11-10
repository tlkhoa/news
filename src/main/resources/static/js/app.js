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
})();
