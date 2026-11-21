package com.codemem.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 静态前端入口：构建产物在 classpath:/static/web/。
 * Spring 只对根路径提供 welcome page，这里显式把 /web 与 /web/ 指到 index.html。
 */
@Controller
public class WebUiController {

    @GetMapping({"/web", "/web/"})
    public String index() {
        return "forward:/web/index.html";
    }

    /**
     * 根路径直接落到主界面，省得记端口后面还要拼 /web/。
     * 用 redirect 而不是 forward，保证页面里的相对资源按 /web/ 解析。
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/web/";
    }
}