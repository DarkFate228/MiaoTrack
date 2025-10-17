package com.example.miaotrack;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends AppCompatActivity {

    private Spinner spinner;
    private TextInputEditText etValue;
    private MaterialButton btnAdd;
    private LineChart chart;
    private ArrayList<Entry> glucoseEntries;
    private LineDataSet glucoseDataSet;
    private LineDataSet forecastDataSet;
    private LineData lineData;

    private static final int MAX_CHART_ENTRIES = 300;
    // ===== Уведомления о глюкозе =====
    private static final String CHANNEL_ID_GLUCOSE = "glucose_alerts";
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 501;
    private static final int NOTIF_ID_HIGH = 1001;
    private static final int NOTIF_ID_LOW = 1002;
    private static final float HIGH_GLUCOSE_THRESHOLD = 11.0f;
    private static final float LOW_GLUCOSE_THRESHOLD = 5.0f;

    private enum AlertState { NORMAL, HIGH, LOW }
    private AlertState lastAlertState = AlertState.NORMAL;
    // ================================

    private static final String[] SCREENS = {"Главный экран", "Текущие значения", "История"};
    private static final String TAG_CHART = "MainActivityChart";
    private static final String TAG_FILE_LOG = "MiaoTrackFileLogMA"; // Отдельный тег для логов файла в MainActivity

    private static final String PREFS_NAME = "GlucosePrefs";
    private static final String PREFS_KEY_ENTRIES = "GlucoseEntries";
    private static final String PREFS_KEY_FORECAST = "ForecastEntries";

    // Время → ось X
    private static final long MINUTE_MS = 60_000L;
    private static final String PREFS_KEY_BASETIME = "BaseTimeMs";
    private long baseTimeMs = -1L;   // точка отсчёта времени (эпоха), чтобы X были «минуты с начала»

    private static final float WINDOW_MINUTES = 3f;

    private static final int REQUEST_CODE_WRITE_EXTERNAL_STORAGE = 100;

    // Получение обновлений глюкозы из сервиса/датчика через LocalBroadcast
    private final BroadcastReceiver glucoseUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && current_data.ACTION_GLUCOSE_UPDATE.equals(intent.getAction())) {
                float glucoseValue = intent.getFloatExtra(current_data.EXTRA_GLUCOSE_VALUE, -1f);
                if (glucoseValue != -1f) {
                    Log.d(TAG_CHART, "MainActivity: Получено обновление глюкозы с датчика: " + glucoseValue);
                    addGlucoseEntry(glucoseValue, false); // false, так как это не ручной ввод
                } else {
                    Log.w(TAG_CHART, "MainActivity: Получено обновление глюкозы, но значение некорректно.");
                }
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Уведомления
        requestNotificationPermissionIfNeeded();
        ensureNotificationChannel();

        spinner = findViewById(R.id.spinner);
        etValue = findViewById(R.id.etGlucoseInput);
        btnAdd = findViewById(R.id.btnSaveManual);
        chart = findViewById(R.id.lineChart);
        chart.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch (View v, MotionEvent event){
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        glucoseEntries = new ArrayList<>();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SCREENS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, android.view.View view, int i, long l) {
                if (spinner.getTag() != null && spinner.getTag() instanceof Integer) {
                    int previousPosition = (Integer) spinner.getTag();
                    if (i == previousPosition) return;
                }
                spinner.setTag(i);
                Class<?> activityClass = null;
                switch (i) {
                    case 0: activityClass = MainActivity.class; break;
                    case 1: activityClass = com.example.miaotrack.current_data.class; break;
                    case 2: activityClass = com.example.miaotrack.history_measure.class; break;
                }
                if (activityClass != null && activityClass != MainActivity.class) {
                    startActivity(new Intent(MainActivity.this, activityClass));
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        });

        btnAdd.setOnClickListener(v -> {
            String valueString = "";
            if (etValue.getText() != null) {
                valueString = etValue.getText().toString().trim();
            }

            if (valueString.isEmpty()) {
                Toast.makeText(this, "Введите значение", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                float value = Float.parseFloat(valueString.replace(',', '.'));
                addGlucoseEntry(value, true); // true, так как это ручной ввод
                etValue.setText("");
                Toast.makeText(MainActivity.this, String.format(Locale.getDefault(), "Значение %.1f добавлено", value), Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "Некорректное число", Toast.LENGTH_SHORT).show();
            }
        });

        setupChart();
        restoreEntries();

        // Регистрируем приёмник обновлений
        LocalBroadcastManager.getInstance(this).registerReceiver(
                glucoseUpdateReceiver,
                new IntentFilter(current_data.ACTION_GLUCOSE_UPDATE)
        );

        // Проверка и запрос разрешения на запись при создании активности
        checkAndRequestStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (spinner != null) {
            spinner.setTag(spinner.getSelectedItemPosition()); // Устанавливаем тег, чтобы избежать начального срабатывания
            spinner.setSelection(0, false); // Устанавливаем "Главный экран" без вызова onItemSelected
        }
        calculateAndSetForecast(); // Пересчитываем прогноз при возвращении
        refreshChartData();
    }

    private void setupChart() {
        final java.text.SimpleDateFormat hhmm =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());

        // Наборы данных
        glucoseDataSet = new LineDataSet(glucoseEntries, "Глюкоза");
        glucoseDataSet.setDrawValues(false);
        glucoseDataSet.setLineWidth(2f);
        glucoseDataSet.setCubicIntensity(0.2f);
        glucoseDataSet.setDrawCircles(false);
        glucoseDataSet.setColor(Color.parseColor("#03A9F4"));

        forecastDataSet = new LineDataSet(new ArrayList<>(), "Прогноз");
        forecastDataSet.setDrawValues(false);
        forecastDataSet.setLineWidth(2f);
        forecastDataSet.setCubicIntensity(0.2f);
        forecastDataSet.setDrawCircles(false);
        forecastDataSet.setColor(Color.parseColor("#FF9800"));
        forecastDataSet.enableDashedLine(10f, 10f, 0);

        lineData = new LineData(glucoseDataSet, forecastDataSet);
        chart.setData(lineData);

        // Жесты и физика графика
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(true);
        chart.setPinchZoom(true);
        chart.setDragDecelerationFrictionCoef(0.9f);

        // ВАЖНО: пусть оси сами подстраиваются под данные
        chart.setAutoScaleMinMaxEnabled(true);

        // Окно видимых значений по X: минимум — чтобы было что «тащить»,
        // максимум — чтобы не показывать сразу «всю историю»
        chart.setVisibleXRangeMinimum(WINDOW_MINUTES); // у тебя это 3f
        chart.setVisibleXRangeMaximum(50f);
        //new
        int onSurface = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface);
        int grid      = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorSurfaceVariant);
        int primary   = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorPrimary);

        // Ось X как время
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                long millis = baseTimeMs + (long) (value * MINUTE_MS);
                return hhmm.format(new java.util.Date(millis));
            }
        });
        //new
        YAxis left = chart.getAxisLeft();
        left.setTextColor(onSurface);
        left.setGridColor(grid);
        left.setAxisLineColor(grid);
        //new
        chart.getLegend().setTextColor(onSurface);
        chart.getDescription().setTextColor(onSurface);
        //new
        for (ILineDataSet s : chart.getData().getDataSets()) {
            if (s instanceof LineDataSet) {
                LineDataSet ds = (LineDataSet) s;
                ds.setColor(primary);
                ds.setCircleColor(primary);
                ds.setValueTextColor(onSurface);
            }
        }
    }



    private void refreshChartData() {
        if (chart == null) return;

        LineData ld = chart.getData();
        if (ld == null) {
            if (glucoseEntries == null) glucoseEntries = new ArrayList<>();
            setupChart();
            calculateAndSetForecast();
            ld = chart.getData();
        }

        glucoseDataSet.notifyDataSetChanged();
        forecastDataSet.notifyDataSetChanged();
        ld.notifyDataChanged();
        chart.notifyDataSetChanged();

        // Размер окна по X
        chart.setVisibleXRangeMinimum(WINDOW_MINUTES); // напр., 3f
        chart.setVisibleXRangeMaximum(50f);

        // Прокрутка к максимальному X между фактами и прогнозом
        float lastX = 0f;
        if (!glucoseEntries.isEmpty()) {
            lastX = glucoseEntries.get(glucoseEntries.size() - 1).getX();
        }
        if (forecastDataSet != null && forecastDataSet.getEntryCount() > 0) {
            float fLast = forecastDataSet.getEntryForIndex(forecastDataSet.getEntryCount() - 1).getX();
            lastX = Math.max(lastX, fLast);
        }
        chart.moveViewToX(lastX);

        chart.invalidate();
    }


    /**
     * Сохранение/восстановление точек графика
     */
    private void saveEntries() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : glucoseEntries) {
                JSONArray pair = new JSONArray();
                pair.put(e.getX());
                pair.put(e.getY());
                arr.put(pair);
            }
            editor.putString(PREFS_KEY_ENTRIES, arr.toString());
            editor.putLong(PREFS_KEY_BASETIME, baseTimeMs);
            JSONArray forecastArr = new JSONArray();
            for (int i = 0; i < forecastDataSet.getEntryCount(); i++) {
                Entry e = forecastDataSet.getEntryForIndex(i);
                JSONArray pair = new JSONArray();
                pair.put(e.getX());
                pair.put(e.getY());
                forecastArr.put(pair);
            }
            editor.putString(PREFS_KEY_FORECAST, forecastArr.toString());
        } catch (Exception ex) {
            Log.e(TAG_CHART, "Ошибка сериализации точек: " + ex.getMessage());
        }
        editor.apply();
    }

    private void restoreEntries() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        baseTimeMs = prefs.getLong(PREFS_KEY_BASETIME, -1L);
        String json = prefs.getString(PREFS_KEY_ENTRIES, null);
        glucoseEntries.clear();
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray pair = arr.getJSONArray(i);
                    float x = (float) pair.getDouble(0);
                    float y = (float) pair.getDouble(1);
                    glucoseEntries.add(new Entry(x, y));
                }
            } catch (JSONException e) {
                Log.e(TAG_CHART, "Ошибка парсинга точек: " + e.getMessage());
            }
        }

        String forecastJson = prefs.getString(PREFS_KEY_FORECAST, null);
        if (forecastJson != null) {
            try {
                JSONArray arr = new JSONArray(forecastJson);
                List<Entry> forecastEntries = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray pair = arr.getJSONArray(i);
                    float x = (float) pair.getDouble(0);
                    float y = (float) pair.getDouble(1);
                    forecastEntries.add(new Entry(x, y));
                }
                forecastDataSet = new LineDataSet(forecastEntries, "Прогноз");
            } catch (JSONException e) {
                Log.e(TAG_CHART, "Ошибка парсинга прогноза: " + e.getMessage());
                forecastDataSet = new LineDataSet(new ArrayList<>(), "Прогноз");
            }
        } else {
            forecastDataSet = new LineDataSet(new ArrayList<>(), "Прогноз");
        }

        if (glucoseDataSet == null) {
            glucoseDataSet = new LineDataSet(glucoseEntries, "Глюкоза");
        } else {
            glucoseDataSet.setValues(glucoseEntries);
        }

        if (lineData == null) {
            lineData = new LineData(glucoseDataSet, forecastDataSet);
        } else {
            lineData.removeDataSet(forecastDataSet);
            lineData.addDataSet(forecastDataSet);
        }

        if (baseTimeMs <= 0 && !glucoseEntries.isEmpty()){
            float lastX = glucoseEntries.get(glucoseEntries.size() - 1).getX();
            long now = System.currentTimeMillis();
            baseTimeMs = now - (long)(lastX * MINUTE_MS);
        }

        chart.setData(lineData);
        chart.invalidate();
    }

    /** Создаёт канал уведомлений для Android 7.0+ */
    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID_GLUCOSE,
                        "Предупреждения о глюкозе",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Уведомления о высоком и низком уровне сахара в крови");
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
        }
    }

    /** Есть ли у приложения разрешение на отправку уведомлений (Android 13+)? */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Запросить разрешение на уведомления при необходимости (Android 13+) */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasNotificationPermission()) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_POST_NOTIFICATIONS
                );
            }
        }
    }

    /** Отправить уведомление */
    private void sendGlucoseNotification(String title, String text, int notificationId) {
        ensureNotificationChannel();
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = (Build.VERSION.SDK_INT >= 31)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_GLUCOSE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(this).notify(notificationId, builder.build());
    }

    /** Проверяем значение и, если вышло за пределы, показываем уведомление (с анти-спам логикой по состояниям) */
    private void maybeNotifyForGlucose(float value) {
        AlertState newState;
        if (value > HIGH_GLUCOSE_THRESHOLD) {
            newState = AlertState.HIGH;
        } else if (value < LOW_GLUCOSE_THRESHOLD) {
            newState = AlertState.LOW;
        } else {
            newState = AlertState.NORMAL;
        }

        if (newState != lastAlertState) {
            if (newState == AlertState.HIGH) {
                String text = String.format(Locale.getDefault(),
                        "Текущее значение: %.1f ммоль/л. Порог > %.1f. Проверьте коррекцию.",
                        value, HIGH_GLUCOSE_THRESHOLD);
                sendGlucoseNotification("Высокий сахар", text, NOTIF_ID_HIGH);
            } else if (newState == AlertState.LOW) {
                String text = String.format(Locale.getDefault(),
                        "Текущее значение: %.1f ммоль/л. Порог < %.1f. Примите меры для повышения.",
                        value, LOW_GLUCOSE_THRESHOLD);
                sendGlucoseNotification("Низкий сахар", text, NOTIF_ID_LOW);
            }
            lastAlertState = newState;
        }
    }

    void addGlucoseEntry(float actualGlucoseValue, boolean isManualEntry) {
        // База времени: первый момент, от которого считаем минуты на оси X
        if (baseTimeMs <= 0L) {
            baseTimeMs = System.currentTimeMillis();
        }

        long now = System.currentTimeMillis();
        float xMinutes = (now - baseTimeMs) / (float) MINUTE_MS;

        // Запомним, был ли пользователь «у правого края», чтобы не ломать ему положение,
        // если он смотрит историю.
        boolean wasAtEnd = isAtRightEDGE();

        // Чистим старый прогноз — он пересчитается ниже
        if (forecastDataSet != null) {
            forecastDataSet.clear();
        }

        // КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: добавляем точку c X = минуты, а не индекс!
        glucoseEntries.add(new Entry(xMinutes, actualGlucoseValue));

        // Проверяем пороги и, при необходимости, уведомляем
        maybeNotifyForGlucose(actualGlucoseValue);

        // Пересчитываем прогноз и обновляем график
        calculateAndSetForecast();
        if (lineData != null) {
            glucoseDataSet.notifyDataSetChanged();
            forecastDataSet.notifyDataSetChanged();
            lineData.notifyDataChanged();
            chart.notifyDataSetChanged();
        }

        // Если пользователь был у правого края — прокручиваемся к последней точке
        if (wasAtEnd && !glucoseEntries.isEmpty()) {
            float lastX = glucoseEntries.get(glucoseEntries.size() - 1).getX();
            chart.moveViewToX(lastX);
        }

        chart.invalidate();
        saveEntries(); // сохраняем точки/эпоху в SharedPreferences

        // Ручной ввод также пишем в файл истории
        if (isManualEntry) {
            appendDataToHistoryFile(actualGlucoseValue, this);
        }
    }


    private boolean isAtRightEDGE(){
        if (glucoseEntries.isEmpty()) return true;
        float lastX = glucoseEntries.get(glucoseEntries.size()-1).getX();
        float right = chart.getHighestVisibleX();

        return right >= lastX - 1f;
    }
    private void calculateAndSetForecast() {
        if (glucoseEntries == null || glucoseEntries.size() < 2) {
            if (forecastDataSet != null) forecastDataSet.clear();
            refreshChartData();
            return;
        }

        int dataSize = glucoseEntries.size();
        if (forecastDataSet == null) {
            forecastDataSet = new LineDataSet(new ArrayList<>(), "Прогноз");
        } else {
            forecastDataSet.clear();
        }

        if (dataSize >= 2) {
            calculateTwoPointForecast(dataSize);
        } else {
            calculateSinglePointForecast(dataSize);
        }

        refreshChartData();
        saveEntries();
    }

    private void calculateTwoPointForecast(int dataSize) {
        try {
            float lastY = glucoseEntries.get(dataSize - 1).getY();
            float prevY = glucoseEntries.get(dataSize - 2).getY();
            float lastX = glucoseEntries.get(dataSize - 1).getX();
            float prevX = glucoseEntries.get(dataSize - 2).getX(); // <-- фикс: раньше брался (dataSize - 1)

            float dx = Math.max(1e-3f, lastX - prevX);
            float slope = (lastY - prevY) / dx;

            float predictedX = lastX + 1f; // «шаг вперёд» на 1 единицу X (1 минута)
            float predictedY = Math.max(0f, lastY + slope * (predictedX - lastX));

            forecastDataSet.addEntry(new Entry(predictedX, predictedY));
            Log.d(TAG_CHART, "Прогноз (2 точки): X=" + predictedX + ", Y=" + predictedY);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG_CHART, "Ошибка индекса при прогнозе по 2 точкам: " + e.getMessage());
        }
    }


    private void calculateSinglePointForecast(int dataSize) {
        try {
            Entry last = glucoseEntries.get(dataSize - 1);
            float predictedY = Math.max(0f,  last.getY());
            float predictedX = last.getX() + 1f;
            forecastDataSet.addEntry(new Entry(predictedX, predictedY));
            Log.d(TAG_CHART, "Прогноз (1 точка): X=" + predictedX + ", Y=" + predictedY);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG_CHART, "Ошибка индекса при прогнозе по 1 точке: " + e.getMessage());
        }
    }


    private void appendDataToHistoryFile(float value, Context ctx) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + " " + String.format(Locale.getDefault(), "%.1f", value) + "\n";


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
// Android 10+ — через MediaStore в Download/glucose_history.txt
            appendViaMediaStore(ctx, line);
        } else {
// Android 9 и ниже — в публичную папку Download (нужно WRITE_EXTERNAL_STORAGE)
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                Log.e(TAG_FILE_LOG, "Папка Download недоступна");
                Toast.makeText(ctx, "Папка Download недоступна", Toast.LENGTH_LONG).show();
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG_FILE_LOG, "Не удалось создать папку Download: " + dir.getAbsolutePath());
                Toast.makeText(ctx, "Не удалось создать папку Download", Toast.LENGTH_LONG).show();
                return;
            }
            File file = new File(dir, "measure_history.txt");
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(line.getBytes());
                Log.d(TAG_FILE_LOG, "(LEGACY) В Download записано: " + line.trim());
            } catch (IOException e) {
                Log.e(TAG_FILE_LOG, "(LEGACY) Ошибка записи в Download: " + e.getMessage());
                Toast.makeText(ctx, "Ошибка записи: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void appendViaMediaStore(Context ctx, String line) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            Uri collection = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            }


            final String relativePath = Environment.DIRECTORY_DOWNLOADS + "/"; // "Download/"
            final String displayName = "measure_history.txt";


// Пытаемся найти файл Download/glucose_history.txt
            String[] projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{relativePath, displayName};


            Uri fileUri = null;
            try (Cursor c = resolver.query(collection, projection, selection, selectionArgs, null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    fileUri = ContentUris.withAppendedId(collection, id);
                }
            }


            if (fileUri == null) {
// Не нашли — создаём в Download/
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                fileUri = resolver.insert(collection, values);
                if (fileUri == null) throw new IOException("resolver.insert вернул null");
            }


// Открываем на дозапись ('wa'), при отсутствии — перезаписываем ('w')
            OutputStream os = null;
            try {
                os = resolver.openOutputStream(fileUri, "wa");
            } catch (Exception appendModeNotSupported) {
                Log.w(TAG_FILE_LOG, "'wa' недоступен, используем 'w' (перезапись)");
                os = resolver.openOutputStream(fileUri, "w");
            }
            if (os == null) throw new IOException("openOutputStream вернул null");
            os.write(line.getBytes());
            os.flush();
            os.close();


            Log.d(TAG_FILE_LOG, "(MediaStore) В Download записано: " + line.trim());
        } catch (Exception e) {
            Log.e(TAG_FILE_LOG, "(MediaStore) Ошибка записи в Download: " + e.getMessage());
            Toast.makeText(ctx, "Ошибка записи в Download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Проверка и запрос разрешения на запись (для API < Q)
    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Разрешение нужно только для версий ниже Android 10
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                // Разрешение не предоставлено, запрашиваем его
                Log.d(TAG_FILE_LOG, "Запрос разрешения WRITE_EXTERNAL_STORAGE");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CODE_WRITE_EXTERNAL_STORAGE);
            } else {
                // Разрешение уже предоставлено
                Log.d(TAG_FILE_LOG, "Разрешение WRITE_EXTERNAL_STORAGE уже есть.");
            }
        } else {
            Log.d(TAG_FILE_LOG, "API >= Q, WRITE_EXTERNAL_STORAGE не требуется (но знайте, что проверка не помешает, но для Downloads обычно работает).");
            // Для API 29+ прямой доступ к Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // обычно работает без явного WRITE_EXTERNAL_STORAGE.
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG_FILE_LOG, "Разрешение WRITE_EXTERNAL_STORAGE предоставлено (MainActivity).");
                Toast.makeText(this, "Разрешение на запись предоставлено.", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG_FILE_LOG, "В разрешении WRITE_EXTERNAL_STORAGE отказано (MainActivity).");
                Toast.makeText(this, "Разрешение на запись не предоставлено — замеры не будут сохраняться в файл.", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на уведомления предоставлено.", Toast.LENGTH_SHORT).show();
                ensureNotificationChannel();
            } else {
                Toast.makeText(this, "Без разрешения уведомления показываться не будут.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(glucoseUpdateReceiver);
    }
}
