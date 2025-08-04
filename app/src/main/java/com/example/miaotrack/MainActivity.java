package com.example.miaotrack; // Убедитесь, что имя пакета ваше

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.Spinner;


import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private static final String[] SCREENS = {"Главный экран", "Текущие значения", "История"};
    private static final String TAG_CHART = "MainActivityChart";
    private static final String TAG_FILE_LOG = "MiaoTrackFileLogMA"; // Отдельный тег для логов файла в MainActivity

    private static final String PREFS_NAME = "GlucosePrefs";
    private static final String PREF_KEY_ENTRIES = "entries";

    // Имя файла истории, должно совпадать с current_data.java и history_measure.java
    public static final String HISTORY_FILENAME = "measure_history.txt";
    private static final int REQUEST_CODE_WRITE_EXTERNAL_STORAGE = 102; // Код для запроса разрешения


    private final BroadcastReceiver glucoseUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (current_data.ACTION_GLUCOSE_UPDATE.equals(intent.getAction())) {
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


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinner = findViewById(R.id.spinner);
        etValue = findViewById(R.id.etGlucoseInput);
        btnAdd = findViewById(R.id.btnSaveManual);
        chart = findViewById(R.id.lineChart);

        glucoseEntries = new ArrayList<>();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SCREENS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (parent.getTag() != null && (Integer)parent.getTag() == position) {
                    return;
                }
                parent.setTag(position);
                Class<?> activityClass = null;
                switch (position) {
                    case 0: break; // Текущий экран
                    case 1: activityClass = com.example.miaotrack.current_data.class; break;
                    case 2: activityClass = com.example.miaotrack.history_measure.class; break;
                }
                if (activityClass != null) {
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
                Toast.makeText(MainActivity.this, "Значение " + String.format(Locale.US, "%.1f", value) + " добавлено", Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Неверный формат числа", Toast.LENGTH_SHORT).show();
            }
        });

        loadEntries();
        setupChart();
        calculateAndSetForecast();
        refreshChartData();

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
        glucoseDataSet = new LineDataSet(glucoseEntries, "Глюкоза");
        glucoseDataSet.setDrawValues(false);
        glucoseDataSet.setLineWidth(2f);
        glucoseDataSet.setCubicIntensity(0.2f);
        glucoseDataSet.setColor(Color.parseColor("#DC3D3d")); // Синий
        glucoseDataSet.setCircleColor(Color.parseColor("#DC3D3D"));
        glucoseDataSet.setCircleRadius(3f);
        glucoseDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        forecastDataSet = new LineDataSet(new ArrayList<>(), "Прогноз");
        forecastDataSet.setDrawValues(true);
        forecastDataSet.setValueTextColor(Color.GRAY);
        forecastDataSet.setValueTextSize(10f);
        forecastDataSet.setLineWidth(2f);
        forecastDataSet.setColor(Color.GRAY);
        forecastDataSet.setCircleColor(Color.GRAY);
        forecastDataSet.setCircleRadius(5f);
        forecastDataSet.setDrawCircleHole(false);

        lineData = new LineData(glucoseDataSet, forecastDataSet);
        chart.setData(lineData);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Color.DKGRAY);
        xAxis.setAxisLineColor(Color.DKGRAY);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.LTGRAY);
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setAxisLineColor(Color.DKGRAY);
        leftAxis.setAxisMinimum(0f);

        chart.getAxisRight().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.getLegend().setTextColor(Color.DKGRAY);
        chart.setTouchEnabled(true);
        chart.setPinchZoom(true);
        chart.setScaleYEnabled(true);
        chart.setDrawGridBackground(false);
    }

    private void addGlucoseEntry(float actualGlucoseValue, boolean isManualEntry) {
        if (forecastDataSet != null) {
            forecastDataSet.clear(); // Очищаем старый прогноз
        }

        glucoseEntries.add(new Entry(glucoseEntries.size(), actualGlucoseValue));

        while (glucoseEntries.size() > MAX_CHART_ENTRIES) {
            if (!glucoseEntries.isEmpty()) {
                glucoseEntries.remove(0);
            } else {
                break;
            }
        }
        // Переиндексируем X координаты
        for (int i = 0; i < glucoseEntries.size(); i++) {
            glucoseEntries.get(i).setX(i);
        }

        calculateAndSetForecast();
        refreshChartData();
        saveEntries(); // Сохранение в SharedPreferences для графика

        // Если это ручной ввод, записываем в файл истории
        if (isManualEntry) {
            appendDataToHistoryFile(actualGlucoseValue, this);
        }
    }

    private void calculateAndSetForecast() {
        if (forecastDataSet == null || glucoseEntries == null) return;
        forecastDataSet.clear();

        int dataSize = glucoseEntries.size();

        if (dataSize >= 3) {
            calculateThreePointForecast(dataSize);
        } else if (dataSize == 2) {
            calculateTwoPointForecast(dataSize);
        }
    }

    private void calculateThreePointForecast(int dataSize) {
        try {
            float y_n = glucoseEntries.get(dataSize - 1).getY();
            float y_n_minus_1 = glucoseEntries.get(dataSize - 2).getY();
            float y_n_minus_2 = glucoseEntries.get(dataSize - 3).getY();

            float slope1 = y_n - y_n_minus_1;
            float slope2 = y_n_minus_1 - y_n_minus_2;

            float averageSlope = (slope1 + slope2) / 2.0f;

            float predictedY = y_n + averageSlope;
            predictedY = Math.max(0f, predictedY);

            float predictedX = dataSize;
            forecastDataSet.addEntry(new Entry(predictedX, predictedY));
            Log.d(TAG_CHART, "Прогноз (3 точки, средний наклон): X=" + predictedX + ", Y=" + predictedY);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG_CHART, "Ошибка индекса при прогнозе по 3 точкам: " + e.getMessage());
            if (dataSize >=2) calculateTwoPointForecast(dataSize);
        }
    }

    private void calculateTwoPointForecast(int dataSize) {
        try {
            float lastActualY = glucoseEntries.get(dataSize - 1).getY();
            float prevActualY = glucoseEntries.get(dataSize - 2).getY();

            float predictedY = lastActualY + (lastActualY - prevActualY);
            predictedY = Math.max(0f, predictedY);

            float predictedX = dataSize;
            forecastDataSet.addEntry(new Entry(predictedX, predictedY));
            Log.d(TAG_CHART, "Прогноз (2 точки): X=" + predictedX + ", Y=" + predictedY);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG_CHART, "Ошибка индекса при прогнозе по 2 точкам: " + e.getMessage());
        }
    }


    private void refreshChartData() {
        if (chart == null) return;

        if (glucoseDataSet == null || forecastDataSet == null || lineData == null) {
            if (glucoseEntries == null) glucoseEntries = new ArrayList<>();
            setupChart();
            calculateAndSetForecast();
        }

        glucoseDataSet.notifyDataSetChanged();
        forecastDataSet.notifyDataSetChanged();
        lineData.notifyDataChanged();
        chart.notifyDataSetChanged();

        float minXRange;
        float maxXRange;
        int dataSize = glucoseEntries.size();

        if (dataSize > 0 || forecastDataSet.getEntryCount() > 0) {
            float currentMaxXOnChart = dataSize > 0 ? glucoseEntries.get(dataSize - 1).getX() : 0;
            if (forecastDataSet.getEntryCount() > 0) {
                Entry forecastEntry = forecastDataSet.getEntryForIndex(0);
                if (dataSize == 0 || forecastEntry.getX() > currentMaxXOnChart) {
                    currentMaxXOnChart = forecastEntry.getX();
                }
            }
            int visiblePointsCount = 20;
            maxXRange = currentMaxXOnChart + 1.5f;
            minXRange = Math.max(0f, maxXRange - visiblePointsCount - 1.5f);
        } else {
            minXRange = 0f;
            maxXRange = 10f;
        }

        chart.getXAxis().setAxisMinimum(minXRange);
        chart.getXAxis().setAxisMaximum(maxXRange);

        if (!glucoseEntries.isEmpty() || forecastDataSet.getEntryCount() > 0) {
            float yMin = Float.MAX_VALUE;
            float yMax = Float.MIN_VALUE;

            for (Entry entry : glucoseEntries) {
                if (entry.getY() < yMin) yMin = entry.getY();
                if (entry.getY() > yMax) yMax = entry.getY();
            }
            if (forecastDataSet.getEntryCount() > 0) {
                Entry forecastEntry = forecastDataSet.getEntryForIndex(0);
                if (forecastEntry.getY() < yMin) yMin = forecastEntry.getY();
                if (forecastEntry.getY() > yMax) yMax = forecastEntry.getY();
            }
            if (yMin == Float.MAX_VALUE) {
                yMin = 0f;
                yMax = 20f;
            }
            chart.getAxisLeft().setAxisMinimum(Math.max(0f, yMin - 1f));
            chart.getAxisLeft().setAxisMaximum(yMax + 1f);
        } else {
            chart.getAxisLeft().setAxisMinimum(0f);
            chart.getAxisLeft().setAxisMaximum(20f);
        }
        chart.invalidate();
    }

    private void saveEntries() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        JSONArray jsonArray = new JSONArray();
        for (Entry e : glucoseEntries) {
            JSONArray pair = new JSONArray();
            try {
                pair.put(e.getX());
                pair.put(e.getY());
            } catch (JSONException ex) {
                Log.e(TAG_CHART, "Ошибка при сохранении точки в JSON: " + ex.getMessage());
                continue;
            }
            jsonArray.put(pair);
        }
        editor.putString(PREF_KEY_ENTRIES, jsonArray.toString());
        editor.apply();
    }

    private void loadEntries() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(PREF_KEY_ENTRIES, null);
        glucoseEntries.clear();
        if (json != null) {
            try {
                JSONArray jsonArray = new JSONArray(json);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONArray pair = jsonArray.getJSONArray(i);
                    glucoseEntries.add(new Entry((float) pair.getDouble(0), (float) pair.getDouble(1)));
                }
                for (int i = 0; i < glucoseEntries.size(); i++) {
                    glucoseEntries.get(i).setX(i);
                }
            } catch (JSONException e) {
                Log.e(TAG_CHART, "Ошибка при загрузке точек из JSON: " + e.getMessage());
                glucoseEntries.clear();
            }
        }
    }

    // Метод для записи данных в файл истории (адаптировано из current_data.java)
    private void appendDataToHistoryFile(float glucoseValue, Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG_FILE_LOG, "Нет разрешения на запись для сохранения истории. Запросите разрешение.");
            // Запрос разрешения должен быть сделан до попытки записи, например, в checkAndRequestStoragePermission()
            // Toast.makeText(context, "Нет разрешения на запись.", Toast.LENGTH_SHORT).show(); // Можно показать Toast
            return;
        }

        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.e(TAG_FILE_LOG, "Внешнее хранилище недоступно: " + state);
            Toast.makeText(context, "Внешнее хранилище недоступно.", Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = timestamp + " " + String.format(Locale.US, "%.1f", glucoseValue) + "\n";

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                Log.e(TAG_FILE_LOG, "Не удалось создать директорию Download.");
                Toast.makeText(context, "Не удалось создать директорию Download.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        File historyFile = new File(downloadsDir, HISTORY_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(historyFile, true)) {
            fos.write(logEntry.getBytes());
            Log.d(TAG_FILE_LOG, "Ручная запись добавлена в " + historyFile.getAbsolutePath() + ": " + logEntry.trim());
            Toast.makeText(context, "Запись сохранена в историю.", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG_FILE_LOG, "Ошибка записи в файл " + historyFile.getAbsolutePath(), e);
            Toast.makeText(context, "Ошибка сохранения истории в файл.", Toast.LENGTH_SHORT).show();
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
            Log.d(TAG_FILE_LOG, "API >= Q, WRITE_EXTERNAL_STORAGE не требуется для доступа к Downloads через MediaStore (но мы используем прямой доступ, так что проверка не помешает, но для Downloads обычно работает).");
            // Для API 29+ прямой доступ к Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // обычно работает без явного WRITE_EXTERNAL_STORAGE, если приложение ориентировано на API 28 или ниже,
            // или если используется requestLegacyExternalStorage="true".
            // Однако, для надежности, можно оставить проверку, но не блокировать функционал если не дано.
            // В данном случае, если не дано, запись просто не произойдет.
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
                Toast.makeText(this, "Разрешение на запись не предоставлено. Ручные замеры не будут сохраняться в файл.", Toast.LENGTH_LONG).show();
            }
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(glucoseUpdateReceiver);
    }
}
