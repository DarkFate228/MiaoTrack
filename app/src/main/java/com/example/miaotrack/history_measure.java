package com.example.miaotrack; // Замените на ваш пакет

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

public class history_measure extends AppCompatActivity {
    private Spinner spinner;
    private TextView tvHistoryContent;

    // Имя файла, из которого будем читать историю.
    // Убедитесь, что это имя совпадает с именем файла в папке Downloads.
    public static final String HISTORY_FILENAME = "measure_history.txt";
    private static final String TAG_HISTORY = "HistoryMeasure";
    private static final int PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 101;

    // Экраны для Spinner
    // Убедитесь, что MainActivity.class и current_data.class существуют и правильно названы.
    private static final String[] SCREENS = {"История", "Главный экран", "Текущие значения"};
    // private static final Class<?>[] ACTIVITY_CLASSES = {history_measure.class, MainActivity.class, current_data.class}; // Если будете использовать

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_measure); // Убедитесь, что у вас есть этот layout файл

        spinner = findViewById(R.id.spinner3); // Убедитесь, что ID spinner правильный
        tvHistoryContent = findViewById(R.id.tvHistoryContent); // Убедитесь, что ID TextView правильный

        // Настройка ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("История измерений");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true); // Показать кнопку "Назад"
        } else {
            // Если ActionBar нет (например, из-за темы NoActionBar), можно установить заголовок окна
            setTitle("История измерений");
        }

        // Настройка Spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SCREENS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0, false); // Устанавливаем "История" по умолчанию, не вызывая onItemSelected

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                // Предотвращаем запуск Intent при инициализации Spinner
                if (parent.getTag() != null && (Integer)parent.getTag() == position) {
                    parent.setTag(null); // Сбрасываем тег после проверки
                    return;
                }
                parent.setTag(position); // Сохраняем текущую позицию для следующей проверки (если нужно)


                Class<?> targetActivity = null;
                switch (position) {
                    case 0:
                        // Текущий экран "История", ничего не делаем или обновляем
                        // loadHistoryFromFile(); // Можно раскомментировать, если нужно перезагружать при выборе "История"
                        break;
                    case 1:
                        targetActivity = MainActivity.class; // Замените на ваш класс MainActivity
                        break;
                    case 2:
                        targetActivity = current_data.class; // Замените на ваш класс current_data
                        break;
                }

                if (targetActivity != null && targetActivity != history_measure.this.getClass()) {
                    Intent intent = new Intent(history_measure.this, targetActivity);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    // finish(); // Опционально: закрыть текущую активность истории
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                // Ничего не делать
            }
        });
        spinner.setTag(0); // Устанавливаем начальный тег для spinner, чтобы избежать срабатывания onItemSelected при запуске

        // Проверяем и запрашиваем разрешение перед загрузкой истории
        checkAndRequestPermission();
    }

    private void checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // Разрешение не предоставлено, запрашиваем его
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        } else {
            // Разрешение уже предоставлено
            loadHistoryFromFile();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Разрешение получено
                Log.d(TAG_HISTORY, "Разрешение на чтение предоставлено.");
                loadHistoryFromFile();
            } else {
                // В разрешении отказано
                Log.w(TAG_HISTORY, "В разрешении на чтение отказано.");
                Toast.makeText(this, "Разрешение на чтение хранилища не предоставлено.", Toast.LENGTH_LONG).show();
                if (tvHistoryContent != null) {
                    tvHistoryContent.setText("Нет разрешения на доступ к файлу истории. Предоставьте разрешение в настройках приложения.");
                }
            }
        }
    }

    private void loadHistoryFromFile() {
        // Проверяем доступность внешнего хранилища для чтения
        if (!isExternalStorageReadable()) {
            Log.e(TAG_HISTORY, "Внешнее хранилище недоступно для чтения.");
            if (tvHistoryContent != null) {
                tvHistoryContent.setText("Внешнее хранилище недоступно.");
            }
            Toast.makeText(this, "Внешнее хранилище недоступно", Toast.LENGTH_SHORT).show();
            return;
        }

        FileInputStream fis = null;
        StringBuilder stringBuilder = new StringBuilder();

        try {
            // Получаем путь к общедоступной папке Downloads
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File historyFile = new File(downloadsDir, HISTORY_FILENAME);

            if (!historyFile.exists()) {
                Log.w(TAG_HISTORY, "Файл истории не найден в Downloads: " + historyFile.getAbsolutePath());
                if (tvHistoryContent != null) {
                    tvHistoryContent.setText("Файл истории '" + HISTORY_FILENAME + "' не найден в папке Downloads.");
                }
                return;
            }

            Log.d(TAG_HISTORY, "Чтение файла: " + historyFile.getAbsolutePath());
            fis = new FileInputStream(historyFile);
            InputStreamReader inputStreamReader = new InputStreamReader(fis);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }

            if (tvHistoryContent != null) {
                if (stringBuilder.length() > 0) {
                    tvHistoryContent.setText(stringBuilder.toString());
                } else {
                    tvHistoryContent.setText("История измерений пуста (файл пуст).");
                }
            } else {
                Log.e(TAG_HISTORY, "tvHistoryContent is null! Не удалось отобразить историю.");
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG_HISTORY, "FileNotFoundException: Файл истории не найден по указанному пути.", e);
            if (tvHistoryContent != null) {
                tvHistoryContent.setText("Файл истории не найден. Убедитесь, что он существует в папке Downloads.");
            }
        } catch (IOException e) {
            Log.e(TAG_HISTORY, "IOException: Ошибка чтения файла истории: " + HISTORY_FILENAME, e);
            if (tvHistoryContent != null) {
                tvHistoryContent.setText("Ошибка чтения файла истории.");
            }
            Toast.makeText(this, "Ошибка чтения истории", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Log.e(TAG_HISTORY, "SecurityException: Нет разрешения на доступ к файлу.", e);
            if (tvHistoryContent != null) {
                tvHistoryContent.setText("Ошибка безопасности: нет разрешения на доступ к файлу. Проверьте разрешения приложения.");
            }
            Toast.makeText(this, "Нет разрешения на доступ к файлу", Toast.LENGTH_LONG).show();
        }
        finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    Log.e(TAG_HISTORY, "Ошибка закрытия FileInputStream", e);
                }
            }
        }
    }

    // Вспомогательный метод для проверки доступности внешнего хранилища для чтения
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }
        Log.w(TAG_HISTORY, "Состояние внешнего хранилища: " + state);
        return false;
    }

    // Обработка нажатия кнопки "Назад" в ActionBar
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Переход к MainActivity или просто закрытие текущей активности
            Intent intent = new Intent(this, MainActivity.class); // Замените на ваш класс MainActivity
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            // finish(); // Если хотите просто закрыть текущую активность
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Обновляем историю при возвращении на экран, если это необходимо
        // (например, если файл мог измениться)
        // checkAndRequestPermission(); // Раскомментируйте, если хотите обновлять каждый раз при возврате

        // Устанавливаем правильный выбор в spinner при возврате на экран
        if (spinner != null) {
            // Сбрасываем тег перед установкой selection, чтобы onItemSelected не сработал, если позиция не изменилась
            spinner.setTag(spinner.getSelectedItemPosition());
            spinner.setSelection(0, false); // Устанавливаем "История", false чтобы не вызывать onItemSelected
        }
    }
}
