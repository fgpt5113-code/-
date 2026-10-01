package io.local.phoneuxlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT_CSV = 10;
    private static final int REQ_EXPORT_JSON = 11;

    private SharedPreferences prefs;
    private LogStore store;
    private TextView status;
    private TextView stats;
    private CheckBox logging;
    private CheckBox verbose;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        store = LogStore.get(this);
        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        logging = findViewById(R.id.loggingEnabled);
        verbose = findViewById(R.id.verboseEnabled);

        logging.setChecked(prefs.getBoolean("logging_enabled", true));
        verbose.setChecked(prefs.getBoolean("verbose_enabled", false));
        logging.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("logging_enabled", isChecked).apply());
        verbose.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("verbose_enabled", isChecked).apply());

        findViewById(R.id.openAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.rawTest).setOnClickListener(v -> startRawTest());
        findViewById(R.id.exportCsv).setOnClickListener(v -> createExport("text/csv", REQ_EXPORT_CSV, "csv"));
        findViewById(R.id.exportJson).setOnClickListener(v -> createExport("application/json", REQ_EXPORT_JSON, "json"));
        findViewById(R.id.clearLog).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Очистить журнал?")
                .setMessage("Это удалит все локально записанные события.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    store.clear();
                    refresh();
                }).show());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean enabled = isAccessibilityServiceEnabled();
        status.setText("Accessibility-сервис: " + (enabled ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                + "\nAndroid API: " + Build.VERSION.SDK_INT
                + "\nСырые координаты: " + (Build.VERSION.SDK_INT >= 34 ? "доступны только в тестовом режиме" : "недоступны на этой версии Android"));
        stats.setText("Записей в журнале: " + store.count());
        Button raw = findViewById(R.id.rawTest);
        raw.setEnabled(Build.VERSION.SDK_INT >= 34 && enabled);
    }

    private void startRawTest() {
        if (Build.VERSION.SDK_INT < 34) {
            Toast.makeText(this, "Нужен Android 14 / API 34 или новее", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Сначала включи Phone UX Logger в спец. возможностях", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Сырые координаты: 10 секунд")
                .setMessage("В этот период приложение получит MotionEvent всего сенсорного экрана. Android не передаёт эти касания остальной системе, поэтому интерфейс временно перестанет реагировать. Через 10 секунд режим отключится автоматически.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Запустить", (d, w) -> {
                    Intent i = new Intent(UxAccessibilityService.ACTION_RAW_TEST);
                    i.setPackage(getPackageName());
                    sendBroadcast(i);
                    Toast.makeText(this, "Тест запущен на 10 секунд", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void createExport(String mime, int requestCode, String ext) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "phoneux-" + stamp + "." + ext);
        startActivityForResult(i, requestCode);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("No output stream");
                if (requestCode == REQ_EXPORT_CSV) store.exportCsv(out);
                else if (requestCode == REQ_EXPORT_JSON) store.exportJson(out);
                runOnUiThread(() -> Toast.makeText(this, "Экспорт завершён", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка экспорта: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, UxAccessibilityService.class);
        String flat = me.flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String s : splitter) if (flat.equalsIgnoreCase(s)) return true;
        return false;
    }
}
