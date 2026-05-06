package com.kaixuan.copilot_ollama_proxy.api;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPageController {

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/config";
        }
        return "admin/login";
    }

    @GetMapping("/config")
    public String config(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "admin/config";
    }
}