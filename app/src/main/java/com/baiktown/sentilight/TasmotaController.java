package com.baiktown.sentilight;

import com.baiktown.sentilight.BuildConfig;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Color; // 💡 HSB <-> RGB 변환을 위해 추가

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Android용 Tasmota + Gemini 제어 컨트롤러 (OkHttp 사용)
 * - Gemini: URL 쿼리 파라미터(?key=...) 방식
 * - Tasmota: HTTP GET /cm?cmnd=... (URL 인코딩 필수)
 */
public class TasmotaController {

    private static final String TAG = "TasmotaController";

    // ========================= 사용자 설정 (초기값) =========================
    private volatile String apiKey;
    // 💡 "gemini-1.5-flash" 모델이 더 빠릅니다. (필요하다면 "gemini-2.5-flash"로 변경 가능)
//    private volatile String geminiModel = "gemini-1.5-flash-lite";
    private volatile String geminiModel = "gemini-2.5-flash-lite";

    private volatile String tasmotaIpAddress = "192.168.0.9";
    private volatile boolean isSimulating = true;
    // ====================================================================

    private static final int WAITING_TIME = 20; // 초 단위
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(WAITING_TIME, TimeUnit.SECONDS) // 연결 타임아웃
            .writeTimeout(WAITING_TIME, TimeUnit.SECONDS)    // 쓰기 타임아웃
            .readTimeout(WAITING_TIME, TimeUnit.SECONDS)     // 읽기 타임아웃
            .callTimeout(WAITING_TIME*2, TimeUnit.SECONDS)     // 전체 타임아웃
            .retryOnConnectionFailure(true)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** BuildConfig의 API 키 초기화를 위한 생성자 */
    public TasmotaController() {
        // BuildConfig 값이 String 타입임을 가정하고 safeString(String s) 호출
        this.apiKey = safeString(BuildConfig.SENTILIGHT_LLM_API_KEY);
    }

    /** 결과 콜백 (메인스레드로 호출) */
    public interface ControllerCallback {
        // FIX 1: 콜백 인터페이스에 색상 값 (RGB int) 추가
        void onSuccess(String command, String tasmotaResponse, String geminiExplanation, int colorRgb);
        void onFailure(String message);
    }

    // -------------------- 외부 설정자 (Setter/Getter) --------------------
    public void setIsSimulating(boolean simulating) { this.isSimulating = simulating; }
    public boolean isSimulating() { return this.isSimulating; }
    public void setTasmotaIpAddress(String ipAddress) { this.tasmotaIpAddress = ipAddress; }
    public void setApiKey(String apiKey) { this.apiKey = safeString(apiKey); }
    public void setGeminiModel(String model) { if (!isBlank(model)) this.geminiModel = model.trim(); }

    // -------------------- 메인 진입점 --------------------
    public void processMoodAndControlLight(String moodText, ControllerCallback callback) {
        executor.execute(() -> {
            String fullGeminiResponse = null;
            String tasmotaCommand = null;
            String geminiExplanation = null;
            int finalColorRgb = 0; // 초기화

            try {
                // 1) Gemini 호출 (기존 안정 로직 유지)
                fullGeminiResponse = generateGeminiResponse(moodText);
                if (isBlank(fullGeminiResponse)) {
                    throw new IOException("Gemini가 빈 응답을 반환했습니다.");
                }

                // 2) [COMMAND:], [EXPLANATION:] 파싱
                tasmotaCommand = extractCommand(fullGeminiResponse);
                geminiExplanation = extractExplanation(fullGeminiResponse, tasmotaCommand);
                Log.d(TAG, "Gemini Command: " + tasmotaCommand);

                // FIX 2: HSBCOLOR 명령에서 정수형 RGB 값 추출
                finalColorRgb = convertHsbToRgb(tasmotaCommand);


                // 3) 실제 전송 (시뮬레이션이면 스킵) (기존 로직 유지)
                String tasmotaResponse = "시뮬레이션 모드(전송 안 함)";
                if (!isSimulating) {
                    ensureIpConfigured();

                    // Tasmota 상태 체크 (선택 사항)
                    sendToTasmotaRaw("Status%2011", false);

                    String encoded = encodeCmndForUrl(tasmotaCommand);
                    tasmotaResponse = sendToTasmotaRaw(encoded, true);
                }

                final String fCmd = tasmotaCommand;
                final String fExp = geminiExplanation;
                final String fResp = tasmotaResponse;
                final int fRgb = finalColorRgb;

                // FIX 3: 콜백에 색상 값 추가
                mainHandler.post(() -> callback.onSuccess(fCmd, fResp, fExp, fRgb));

            } catch (Exception e) {
                Log.e(TAG, "조명 제어 오류", e);
                final String fCmd = (tasmotaCommand != null) ? tasmotaCommand : "N/A";
                final String msg = "명령: " + fCmd + " / 오류: " + e.getMessage();
                mainHandler.post(() -> callback.onFailure(msg));
            }
        });
    }

    // -------------------- Gemini 호출부 --------------------
    private String generateGeminiResponse(String userInput) throws IOException {
        final String key = this.apiKey;
        if (isBlank(key)) {
            throw new IOException("Gemini API 키가 설정되지 않았습니다. setApiKey(...) 또는 BuildConfig 값을 확인하세요.");
        }

        // 🚨 404 에러 방지를 위해 URL 구성 로직 강화 (models/ 접두사 처리)
        final String modelName = this.geminiModel.startsWith("models/")
                ? this.geminiModel
                : "models/" + this.geminiModel;

        // URL 구성 (API 키를 쿼리 파라미터로 추가)
        final String base = "https://generativelanguage.googleapis.com/v1/" + modelName + ":generateContent";

        // Android 호환성을 위해 StandardCharsets.UTF_8.toString() 사용
        final String urlWithKey = base + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.toString());

        String prompt =
                "사용자 기분: '" + userInput + "'. 이를 Tasmota 전구 제어 명령으로 변환하세요. " +
                        "결과 형식은 [COMMAND: HSBCOLOR hue,saturation,brightness;Dimmer value;CT temperature] " +
                        "[EXPLANATION: 기분 변화에 대한 설명] 으로만 출력하세요. " +
                        "(hue:0-359, saturation/brightness:0-100, Dimmer:0-100, CT:153-500). " +
                        "예: [COMMAND: HSBCOLOR 60,100,100;Dimmer 70;CT 250] [EXPLANATION: 밝고 따뜻한 노란색으로 활력을 줍니다.]";

        // JSON 구성 (role: user)
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);

        JsonArray contentsArray = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray partsArray = new JsonArray();
        partsArray.add(part);
        content.add("parts", partsArray);
        contentsArray.add(content);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contentsArray);

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);

        Request request = new Request.Builder()
                .url(urlWithKey)
                .post(body)
                .build();

        // 간단 재시도
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Response response = client.newCall(request).execute()) {
                String responseString = (response.body() != null) ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Gemini API 오류: HTTP " + response.code() + " / " + responseString);
                }

                // 응답 파싱의 안전성 강화
                JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);
                if (jsonResponse == null || !jsonResponse.has("candidates") || jsonResponse.getAsJsonArray("candidates").size() == 0) {
                    throw new IOException("Gemini 응답이 비어 있거나 후보가 없습니다.");
                }

                JsonObject contentObj = jsonResponse.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content");
                if (contentObj == null || !contentObj.has("parts")) {
                    throw new IOException("Gemini 응답 파싱 실패(content/parts 없음).");
                }

                String generatedText = contentObj.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                if (isBlank(generatedText)) {
                    throw new IOException("Gemini가 텍스트를 생성하지 못했습니다.");
                }
                return generatedText.trim();

            } catch (IOException e) {
                last = e;
                try { Thread.sleep(300L); } catch (InterruptedException ignored) {}
            }
        }
        throw last != null ? last : new IOException("Gemini 호출 실패(원인 불명)");
    }

// -------------------- HSB <-> RGB 변환 유틸리티 --------------------
    /** HSB 값을 Android RGB 정수값으로 변환합니다. */
    private int convertHsbToRgb(String hsbCommand) {
        try {
            // HSBCOLOR H,S,B 패턴을 찾습니다.
            Pattern pattern = Pattern.compile("HSBCOLOR\\s*(\\d+),(\\d+),(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(hsbCommand);

            if (matcher.find()) {
                float h = Float.parseFloat(matcher.group(1)); // Hue (0-359)
                float s = Float.parseFloat(matcher.group(2)) / 100f; // Saturation (0.0 - 1.0)
                float v = Float.parseFloat(matcher.group(3)) / 100f; // Value/Brightness (0.0 - 1.0)

                return Color.HSVToColor(new float[]{h, s, v});
            }
        } catch (Exception e) {
            Log.e(TAG, "HSB to RGB conversion failed in command: " + hsbCommand, e);
        }
        // 변환 실패 시 기본값 (약간 어두운 파란색, #181B1C)
        return Color.parseColor("#181B1C");
    }


// -------------------- 파싱기 --------------------
    /** [COMMAND: ...] 블록에서 명령 추출 (허용 문자만 유지) */
    private String extractCommand(String fullResponse) {
        Pattern pattern = Pattern.compile("\\[COMMAND:\\s*(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(fullResponse);
        if (matcher.find() && matcher.group(1) != null) {
            String raw = matcher.group(1).trim();
            String cleaned = raw.replaceAll("\\s+", " ");
            cleaned = cleaned.replaceAll("[^A-Za-z0-9,;\\s]", "");
            if (!cleaned.toUpperCase().contains("HSBCOLOR")) {
                // HSBCOLOR 명령이 없으면 기본값 설정 (Gemini가 포맷을 지키지 않았을 때)
                return "HSBCOLOR 60,100,100;Dimmer 70;CT 250";
            }
            return cleaned;
        }
        // 명령 자체를 찾지 못했으면 안전한 끄기 명령 반환
        return "HSBCOLOR 0,0,0;Dimmer 0;CT 500";
    }

    /** [EXPLANATION: ...] 블록에서 설명 추출 */
    private String extractExplanation(String fullResponse, String command) {
        Pattern pattern = Pattern.compile("\\[EXPLANATION:\\s*(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(fullResponse);
        if (matcher.find() && matcher.group(1) != null) {
            return matcher.group(1).trim();
        }
        return command + " 명령을 생성했습니다. (설명 없음)";
    }

    // -------------------- Tasmota 전송부 --------------------
    private void ensureIpConfigured() throws IOException {
        if (isBlank(tasmotaIpAddress)) {
            throw new IOException("Tasmota IP 주소가 설정되지 않았습니다.");
        }
    }

    /** 인코딩된 cmnd를 그대로 GET 호출 */
    private String sendToTasmotaRaw(String encodedCmnd, boolean throwOnNon200) throws IOException {
        String url = "http://" + this.tasmotaIpAddress + "/cm?cmnd=" + encodedCmnd;
        Request req = new Request.Builder().url(url).get().build();

        // 간단 재시도
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Response resp = client.newCall(req).execute()) {
                String body = (resp.body() != null) ? resp.body().string() : "";
                if (!resp.isSuccessful() && throwOnNon200) {
                    throw new IOException("Tasmota 전송 실패: HTTP " + resp.code() + " / URL: " + url + " / " + body);
                }
                return body;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(200L); } catch (InterruptedException ignored) {}
            }
        }
        throw last != null ? last : new IOException("Tasmota 호출 실패(원인 불명)");
    }

    /** 세미콜론 등 포함 명령을 URL-safe 하게 인코딩 */
    private static String encodeCmndForUrl(String rawCmnd) {
        // StandardCharsets.UTF_8를 사용하면 Java 7 이상에서 안정적입니다.
        return URLEncoder.encode(rawCmnd, StandardCharsets.UTF_8);
    }

    // -------------------- 유틸: 프리셋 전송 --------------------
    public void sendPreset(String hsbc, int dimmer, int ct, ControllerCallback callback) {
        String cmd = "HSBCOLOR " + hsbc + ";Dimmer " + dimmer + ";CT " + ct;
        executor.execute(() -> {
            try {
                // 색상 값 추출
                int finalColorRgb = convertHsbToRgb(cmd);

                String resp = isSimulating
                        ? "시뮬레이션 모드(전송 안 함)"
                        : sendToTasmotaRaw(encodeCmndForUrl(cmd), true);
                final String fResp = resp;

                // 콜백에 색상 값 추가
                mainHandler.post(() -> callback.onSuccess(cmd, fResp, "프리셋 적용", finalColorRgb));
            } catch (Exception e) {
                final String msg = "명령: " + cmd + " / 오류: " + e.getMessage();
                mainHandler.post(() -> callback.onFailure(msg));
            }
        });
    }

    // -------------------- 내부 유틸 --------------------
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    // safeString(Object s) 오버로드를 제거하여 생성자의 모호성을 방지합니다.
    // private static String safeString(Object s) {
    //     return s == null ? "" : String.valueOf(s);
    // }
}