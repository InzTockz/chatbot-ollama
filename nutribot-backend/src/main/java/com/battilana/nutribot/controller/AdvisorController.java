package com.battilana.nutribot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HU07: derivacion a un asesor humano por WhatsApp mediante enlace wa.me.
 * El numero del asesor se configura en application.yml (nutribot.whatsapp.advisor-number).
 */
@RestController
@RequestMapping("/api/advisor")
public class AdvisorController {

    @Value("${nutribot.whatsapp.advisor-number:51999888777}")
    private String advisorNumber;

    @GetMapping("/whatsapp")
    public Map<String, String> whatsapp(
            @RequestParam(defaultValue = "Hola, vengo del chat de NutriBot y me gustaria hablar con un asesor.")
            String message) {
        String url = "https://wa.me/" + advisorNumber
                + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        return Map.of("phone", advisorNumber, "url", url);
    }
}
