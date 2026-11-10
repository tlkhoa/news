package com.tdk.news.controller;

import java.time.Year;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Dữ liệu chung đưa vào mọi trang (thay cho biểu thức #calendars.now().year không hỗ trợ). */
@ControllerAdvice
public class GlobalData {

    @ModelAttribute
    public void addCommonAttributes(Model model) {
        model.addAttribute("currentYear", Year.now().getValue());
    }
}
