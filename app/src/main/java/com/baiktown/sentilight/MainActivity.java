package com.baiktown.sentilight;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.widget.FrameLayout;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ColorFilter;

// 💡 Lottie/Android Color Filter Imports (Modified)
import android.graphics.PorterDuff; // 🌟 추가: PorterDuff Mode를 사용하기 위해
import android.graphics.PorterDuffColorFilter; // 🌟 SimpleLottieColorFilter 대신 사용

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieValueCallback;
import com.airbnb.lottie.LottieProperty;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements RecognitionListener {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    // 애니메이션 상수
    private static final float SCALE_UP_FACTOR = 1.05f;
    private static final long ANIMATION_DURATION = 100;

    // UI 요소
    private TextView resultTextView;
    private TextView modeToggleButton;
    private TextView modeStatusView;
    private EditText ipInputView;
    private View rootView;

    private ImageView backgroundIconView;    // 사용자가 터치할 전구 이미지
    private LottieAnimationView lottieAnimationView; // 애니메이션 효과를 보여줄 Lottie 뷰

    // 💡 전구 컨테이너 (FrameLayout)
    private FrameLayout lightContainer;

    // 배경색
    private static final int INITIAL_BACKGROUND_COLOR = Color.parseColor("#4285F4");

    // 음성 인식 요소
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

    // Tasmota 제어 요소
    private TasmotaController tasmotaController;
    private String tasmotaIpAddress = "192.168.0.9";

    private String[] permissions = {Manifest.permission.RECORD_AUDIO};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 뷰 초기화
        rootView = findViewById(android.R.id.content).getRootView();
        rootView.setBackgroundColor(INITIAL_BACKGROUND_COLOR);
        resultTextView = findViewById(R.id.resultTextView);
        modeToggleButton = findViewById(R.id.modeToggleButton);
        modeStatusView = findViewById(R.id.modeStatusView);
        ipInputView = findViewById(R.id.ipInputView);

        backgroundIconView = findViewById(R.id.backgroundIconView);
        lottieAnimationView = findViewById(R.id.animatedIconView);

        // 💡 lightContainer 초기화
        lightContainer = findViewById(R.id.lightContainer);


        // TasmotaController 초기화
        tasmotaController = new TasmotaController();
        tasmotaController.setIsSimulating(true);
        ipInputView.setText(tasmotaIpAddress);
        tasmotaController.setTasmotaIpAddress(tasmotaIpAddress);

        // 권한 요청
        requestAudioPermission();

        // SpeechRecognizer 설정
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(this);
        } else {
            Toast.makeText(this, "음성 인식 서비스가 지원되지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        // 음성 인식 Intent 설정
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // 클릭/터치 리스너
        backgroundIconView.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                startListening();
            }
        });

        backgroundIconView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                animateScale(v, SCALE_UP_FACTOR, ANIMATION_DURATION);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                animateScale(v, 1.0f, ANIMATION_DURATION);
            }
            return false; // 클릭 이벤트가 호출되도록 false 반환
        });

        // 모드 토글 버튼 리스너
        modeToggleButton.setOnClickListener(v -> {
            String currentIp = ipInputView.getText().toString();
            tasmotaController.setTasmotaIpAddress(currentIp);
            tasmotaIpAddress = currentIp;
            boolean newMode = !tasmotaController.isSimulating();
            tasmotaController.setIsSimulating(newMode);
            updateModeButton(newMode);
            Toast.makeText(MainActivity.this, newMode ? "시뮬레이션 모드 활성화" : "실제 제어 모드 활성화", Toast.LENGTH_SHORT).show();
        });

        updateModeButton(tasmotaController.isSimulating());
    }

    // 💡 보색을 계산하는 헬퍼 함수 추가
    private int getComplementaryColor(int color) {
        // 알파 채널은 그대로 유지하고, RGB만 반전
        int alpha = Color.alpha(color);
        int red = 255 - Color.red(color);
        int green = 255 - Color.green(color);
        int blue = 255 - Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    // 💡 Lottie 색상 필터를 적용/제거하는 함수 추가
    private void setLottieColorFilter(int color) {
        // Lottie 5.0.0 이후 버전에서는 PorterDuffColorFilter 사용
        ColorFilter filter = color == INITIAL_BACKGROUND_COLOR
                ? null // 초기화 시 null을 전달하여 필터 제거
                : new PorterDuffColorFilter(getComplementaryColor(color), PorterDuff.Mode.SRC_ATOP); // 🌟 변경된 필터 사용

        LottieValueCallback<ColorFilter> colorFilterCallback = new LottieValueCallback<>(filter);

        // 모든 Lottie 요소에 필터 적용
        lottieAnimationView.addValueCallback(
            new KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            colorFilterCallback
        );
    }

    // 💡 lightContainer 배경색을 안전하게 변경하는 함수 추가
    private void setLightContainerColor(int colorRgb) {
        if (lightContainer == null) return;

        // 둥근 사각형 Drawable(GradientDrawable)을 유지하면서 색상만 변경
        if (lightContainer.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) lightContainer.getBackground().mutate()).setColor(colorRgb);
        } else {
            // Drawable이 없는 경우를 대비
            lightContainer.setBackgroundColor(colorRgb);
        }
    }


    private void animateScale(View view, float scale, long duration) {
        view.animate().scaleX(scale).scaleY(scale).setDuration(duration).start();
    }

    private void updateModeButton(boolean isSimulating) {
        if (isSimulating) {
            modeToggleButton.setText("실제 전구 제어로 전환");
            modeStatusView.setText("현재 모드: 시뮬레이션 (명령어 출력)");
            ipInputView.setEnabled(false);
        } else {
            modeToggleButton.setText("시뮬레이션 모드로 전환");
            modeStatusView.setText("현재 모드: 실제 제어 (" + tasmotaIpAddress + ")");
            ipInputView.setEnabled(true);
        }
    }

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
            resultTextView.setText("말씀해주세요...");
        } else {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            requestAudioPermission();
        }
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isListening = false;
        lottieAnimationView.cancelAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);

        // 🚨 음성 인식이 중단되면 Lottie 색상 필터 초기화 (선택적)
        setLottieColorFilter(INITIAL_BACKGROUND_COLOR);
    }

    // ------------------- RecognitionListener 콜백 메소드 -------------------

    @Override
    public void onReadyForSpeech(Bundle params) {
        resultTextView.setText("음성 인식 준비 완료. 말하세요...");
        lottieAnimationView.setVisibility(View.VISIBLE);
        lottieAnimationView.playAnimation();
    }

    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { resultTextView.setText("처리 중..."); }

    @Override
    public void onError(int error) {
        lottieAnimationView.cancelAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);
        isListening = false;

        // 🚨 lightContainer와 Lottie 색상 필터 초기화
        setLightContainerColor(INITIAL_BACKGROUND_COLOR);
        setLottieColorFilter(INITIAL_BACKGROUND_COLOR);

        String message;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: message = "일치하는 결과를 찾을 수 없습니다."; break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: message = "말씀이 없어 시간 초과되었습니다."; break;
            case SpeechRecognizer.ERROR_SERVER: message = "서버 오류 (오프라인 팩 확인 필요)"; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: message = "인식 서비스 사용 중입니다."; break;
            case SpeechRecognizer.ERROR_AUDIO: message = "오디오 녹음 오류 (마이크 권한 재확인)"; break;
            default: message = "알 수 없는 오류 발생: " + error; break;
        }
        resultTextView.setText("오류: " + message);
        Toast.makeText(this, "음성 인식 오류: " + message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onResults(Bundle results) {
        isListening = false;

        ArrayList<String> data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (data != null && !data.isEmpty()) {
            final String recognizedText = data.get(0);
            resultTextView.setText("인식: " + recognizedText + "\n조명 명령 생성 및 처리 중...");

            tasmotaController.processMoodAndControlLight(recognizedText, new TasmotaController.ControllerCallback() {
                @Override
                public void onSuccess(String command, String tasmotaResponse, String geminiExplanation, int colorRgb) {
                    lottieAnimationView.cancelAnimation();
                    lottieAnimationView.setVisibility(View.INVISIBLE);

                    // 🌟 lightContainer 배경색 변경 및 Lottie 보색 적용 🌟
                    setLightContainerColor(colorRgb);
                    setLottieColorFilter(colorRgb);

                    if (tasmotaController.isSimulating()) {
                        resultTextView.setText(
                                "인식: " + recognizedText + "\n" +
                                        "COMMAND: " + command + "\n" +
                                        "설명: " + geminiExplanation + "\n" +
                                        "--- [시뮬레이션 완료] ---"
                        );
                        Toast.makeText(MainActivity.this, "시뮬레이션 성공", Toast.LENGTH_SHORT).show();
                    } else {
                        resultTextView.setText(
                                "인식: " + recognizedText + "\n" +
                                        "COMMAND: " + command + "\n" +
                                        "전구 응답: " + (tasmotaResponse.length() > 50 ? "성공" : tasmotaResponse)
                        );
                        Toast.makeText(MainActivity.this, "조명 제어 성공!", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(String message) {
                    lottieAnimationView.cancelAnimation();
                    lottieAnimationView.setVisibility(View.INVISIBLE);

                    // 🚨 lightContainer와 Lottie 색상 필터 초기화
                    setLightContainerColor(INITIAL_BACKGROUND_COLOR);
                    setLottieColorFilter(INITIAL_BACKGROUND_COLOR);

                    resultTextView.setText("인식: " + recognizedText + "\n실패: " + message);
                    Toast.makeText(MainActivity.this, "조명 제어 실패", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            resultTextView.setText("결과 없음");
            Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPartialResults(Bundle partialResults) { /* 기존 코드와 동일 */ }
    @Override public void onEvent(int eventType, Bundle params) { /* 기존 코드와 동일 */ }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "마이크 권한 승인됨.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "마이크 권한이 거부되었습니다. 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }
}