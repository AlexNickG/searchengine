package searchengine.dto.captcha;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
@Setter
public class CaptchaChallenge {
    private String id;
    private String pageUrl;
    private String imageBase64;
    private String formAction;
    private Map<String, String> hiddenFields;
    private String captchaFieldName;
}
