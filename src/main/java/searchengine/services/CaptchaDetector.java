package searchengine.services;

import org.springframework.stereotype.Component;

@Component
public class CaptchaDetector {

    public enum BlockType { NONE, RATE_LIMIT, CAPTCHA_IMAGE, CLOUDFLARE, BLOCKED, SESSION_EXPIRED }

    public BlockType detect(int statusCode, String html) {
        if (statusCode == 429) return BlockType.RATE_LIMIT;
        if (statusCode == 503 && html.toLowerCase().contains("cloudflare")) return BlockType.CLOUDFLARE;
        if (statusCode == 403) return BlockType.BLOCKED;
        String lower = html.toLowerCase();
        if (lower.contains("время жизни сессии") || lower.contains("вернуться на форму поиска")) {
            return BlockType.SESSION_EXPIRED;
        }
        if (lower.contains("captcha") || lower.contains("каптча")) return BlockType.CAPTCHA_IMAGE;
        return BlockType.NONE;
    }
}
