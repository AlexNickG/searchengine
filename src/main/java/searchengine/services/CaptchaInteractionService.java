package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import searchengine.config.ConnectionSettings;
import searchengine.dto.captcha.CaptchaChallenge;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaInteractionService {

    private final ConnectionSettings connectionSettings;

    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Semaphore semaphore = new Semaphore(1);
    private volatile CaptchaChallenge currentChallenge = null;

    public record SolvedChallenge(CaptchaChallenge challenge, String solution) {}

    /**
     * Ждёт решения CAPTCHA от оператора.
     * Возвращает SolvedChallenge с challenge!=null — мы решили сами, нужно отправить форму.
     * Возвращает SolvedChallenge с challenge==null — другая задача решила, просто повторить запрос.
     * Возвращает null — таймаут или прерывание, пропустить страницу.
     */
    public SolvedChallenge awaitSolution(String url, Document captchaPage, Map<String, String> sessionCookies) {
        if (!semaphore.tryAcquire()) {
            // Другая задача уже решает CAPTCHA — ждём завершения и повторяем с новыми cookies
            log.info("CAPTCHA resolution in progress, waiting before retry: {}", url);
            try {
                boolean acquired = semaphore.tryAcquire(6, TimeUnit.MINUTES);
                if (acquired) semaphore.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SolvedChallenge(null, null);
        }
        try {
            CaptchaChallenge challenge = extractChallenge(url, captchaPage, sessionCookies);
            CompletableFuture<String> future = new CompletableFuture<>();
            pending.put(challenge.getId(), future);
            currentChallenge = challenge;
            log.info("CAPTCHA detected — waiting for operator input: {}", url);
            try {
                String solution = future.get(5, TimeUnit.MINUTES);
                return new SolvedChallenge(challenge, solution);
            } catch (TimeoutException e) {
                log.warn("CAPTCHA not solved within 5 minutes, skipping: {}", url);
                return null;
            } catch (InterruptedException | ExecutionException e) {
                log.warn("CAPTCHA solving interrupted: {}", e.getMessage());
                return null;
            } finally {
                pending.remove(challenge.getId());
                currentChallenge = null;
            }
        } finally {
            semaphore.release();
        }
    }

    public void solve(String id, String solution) {
        CompletableFuture<String> future = pending.get(id);
        if (future != null) {
            future.complete(solution);
        } else {
            log.warn("No pending CAPTCHA challenge with id: {}", id);
        }
    }

    public CaptchaChallenge getCurrentChallenge() {
        return currentChallenge;
    }

    private CaptchaChallenge extractChallenge(String requestUrl, Document page, Map<String, String> sessionCookies) {
        // Берём реальный URL страницы после редиректов (JSoup заполняет его при fetch)
        String actualPageUrl = page.location();
        if (actualPageUrl == null || actualPageUrl.isEmpty()) actualPageUrl = requestUrl;

        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setPageUrl(actualPageUrl);

        Element form = page.selectFirst("form");
        Element captchaInput = form != null ? form.selectFirst(
                "input[name=captcha], input[name*=captcha], input[name=code], input[name*=verify], " +
                "input[name*=answer]") : null;

        Element captchaImg = null;
        // 1. Image near the captcha input — настоящая капча всегда рядом со своим input
        if (captchaInput != null) {
            Element node = captchaInput.parent();
            while (node != null) {
                Element img = node.selectFirst("img");
                if (img != null) { captchaImg = img; break; }
                node = node.parent();
            }
        }
        // 2. Fallback: image with explicit captcha-ish src or alt
        if (captchaImg == null) {
            captchaImg = page.selectFirst(
                    "img[src*=captcha], img[src*=Captcha], img[src*=CAPTCHA], " +
                    "img[src*=code], img[src*=verify], img[src*=check], " +
                    "img[alt*=captcha], img[alt*=код], img[alt*=капч]");
        }

        if (captchaImg != null) {
            String rawSrc = captchaImg.attr("src");
            if (rawSrc != null && rawSrc.trim().toLowerCase().startsWith("data:")) {
                // Картинка встроена прямо в HTML — нормализуем пробелы и отдаём как есть
                String trimmed = rawSrc.trim();
                int comma = trimmed.indexOf(',');
                String dataUrl = (comma > 0)
                        ? trimmed.substring(0, comma + 1).replaceAll("\\s+", "")
                                + trimmed.substring(comma + 1).replaceAll("\\s+", "")
                        : trimmed.replaceAll("\\s+", "");
                challenge.setImageBase64(dataUrl);
                log.info("CAPTCHA image extracted from inline data URL ({} chars)", dataUrl.length());
            } else {
                String imgSrc = resolveUrl(captchaImg.absUrl("src"), captchaImg.attr("src"), actualPageUrl);
                if (imgSrc != null) {
                    log.info("Downloading CAPTCHA image from: {}", imgSrc);
                    challenge.setImageBase64(downloadImageAsBase64(imgSrc, actualPageUrl, sessionCookies));
                    if (challenge.getImageBase64() == null) {
                        log.warn("Failed to download CAPTCHA image, falling back to URL-only modal");
                    }
                } else {
                    log.warn("Could not determine CAPTCHA image URL, img element: {}", captchaImg.outerHtml());
                }
            }
        } else {
            log.warn("No CAPTCHA image element found on page: {}", actualPageUrl);
        }

        if (form != null) {
            String action = resolveUrl(form.absUrl("action"), form.attr("action"), actualPageUrl);
            challenge.setFormAction(action != null ? action : actualPageUrl);

            Map<String, String> hiddenFields = new HashMap<>();
            form.select("input[type=hidden]").forEach(input -> {
                String name = input.attr("name");
                if (!name.isEmpty()) hiddenFields.put(name, input.attr("value"));
            });
            challenge.setHiddenFields(hiddenFields);

            if (captchaInput != null) {
                challenge.setCaptchaFieldName(captchaInput.attr("name"));
            }
        }

        return challenge;
    }

    /** Возвращает абсолютный URL: сначала пробует absUrl, затем вручную склеивает с базовым. */
    private String resolveUrl(String absUrl, String rawAttr, String baseUrl) {
        if (absUrl != null && !absUrl.isEmpty()) return absUrl;
        if (rawAttr == null || rawAttr.isEmpty()) return null;
        if (rawAttr.startsWith("http://") || rawAttr.startsWith("https://")) return rawAttr;
        if (rawAttr.startsWith("//")) return "https:" + rawAttr;
        try {
            return new java.net.URL(new java.net.URL(baseUrl), rawAttr).toString();
        } catch (Exception e) {
            log.warn("Cannot resolve URL '{}' against base '{}': {}", rawAttr, baseUrl, e.getMessage());
            return null;
        }
    }

    private String downloadImageAsBase64(String imgUrl, String referrer, Map<String, String> cookies) {
        try {
            Connection conn = Jsoup.connect(imgUrl)
                    .referrer(referrer)
                    .userAgent(connectionSettings.getUserAgent())
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(10_000);
            if (cookies != null && !cookies.isEmpty()) {
                conn.cookies(cookies);
            }
            Connection.Response response = conn.execute();
            byte[] bytes = response.bodyAsBytes();
            if (bytes == null || bytes.length == 0) {
                log.warn("CAPTCHA image is empty at {}", imgUrl);
                return null;
            }
            String contentType = response.contentType();
            if (contentType == null || contentType.isEmpty()) contentType = "image/png";
            String mimeType = contentType.split(";")[0].trim();
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.warn("Failed to download CAPTCHA image from {}: {}", imgUrl, e.getMessage());
            return null;
        }
    }
}
