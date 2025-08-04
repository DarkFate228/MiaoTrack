package com.example.miaotrack; // Изменено для соответствия MainActivity

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class current_data extends AppCompatActivity {
    private static final String TAG = "MiaoMiaoBLE_Static";
    private static final String DEV_NAME_PART = "miaomiao2_c233";

    private static final UUID UUID_NRF_DATA_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CHARACTERISTIC_RX_FROM_MIAOMIAO = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CHARACTERISTIC_TX_TO_MIAOMIAO = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte CMD_START_DATA_STREAM = (byte) 0xF0;
    private static final byte[] CMD_ALLOW_SENSOR = new byte[]{(byte) 0xD3, (byte) 0x01};
    private static final String[] SCREENS = {"Текущие значения", "Главный экран", "История"};

    // Статические поля для управления Bluetooth состоянием
    private static BluetoothAdapter staticBluetoothAdapter;
    private static BluetoothLeScanner staticBleScanner;
    private static BluetoothGatt staticBluetoothGatt;
    private static BluetoothDevice staticConnectedDevice;
    private static final ArrayList<Byte> staticPacketBuffer = new ArrayList<>();
    private static MiaoMiaoPacket staticLastReceivedPacket = null;
    private static boolean staticIsScanning = false;
    private static Handler staticMainHandler;
    private static WeakReference<current_data> currentActivityInstanceRef;

    // UI элементы
    private Spinner spinner;
    private TextView tvLog;
    private Button btnScan;
    private TextView tvConnectedDeviceHeader;
    private TextView tvDeviceName;
    private TextView tvBattery;
    private TextView tvGlucoseData;
    private TextView tvFirmwareVersion;
    private Button btnDisconnect;
    private TextView tvCurrentCorrectionLabel;
    private TextView tvCurrentCorrection;
    private EditText etCorrectionValue;
    private Button btnApplyCorrection;

    private boolean instanceFirstPacketDisplayed = false;

    // Для сохранения коррекции
    private float currentGlucoseCorrection = 0.0f;
    private static final String PREFS_NAME = "MiaoMiaoPrefs";
    private static final String CORRECTION_KEY = "glucoseCorrection";
    private static final float MG_DL_TO_MMOL_L_FACTOR = 18.0182f;

    private static final long SCAN_PERIOD = 15000;
    private static final int REQUEST_PERMISSIONS_CODE = 42;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 103; // Код для запроса разрешения на уведомления

    // Для LocalBroadcastManager
    public static final String ACTION_GLUCOSE_UPDATE = "com.example.miaotrack.ACTION_GLUCOSE_UPDATE";
    public static final String EXTRA_GLUCOSE_VALUE = "com.example.miaotrack.EXTRA_GLUCOSE_VALUE";
    public static final String HISTORY_FILENAME = "measure_history.txt";
    private static final String TAG_FILE_LOG = "MiaoTrackFileLog";

    // Для автоматического опроса
    private static final long POLLING_INTERVAL = 5 * 60 * 1000;
    private static Runnable staticPollingRunnable;
    private static boolean isPollingScheduled = false;

    // Для уведомлений
    public static final String GLUCOSE_CHANNEL_ID = "glucose_notification_channel";
    public static final int GLUCOSE_NOTIFICATION_ID = 1;


    // ================== СТАТИЧЕСКИЕ КОЛБЭКИ ==================
    private static final ScanCallback staticScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = null;
            Context context = getContextFromRef();
            if (context != null && (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                name = dev.getName();
            } else if (context == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                try { name = dev.getName(); } catch (SecurityException e) { Log.e(TAG, "SecurityException on dev.getName() without context"); }
            }

            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;

            if (name != null && name.toLowerCase().contains(DEV_NAME_PART.toLowerCase())) {
                if (activity != null) activity.log("Найдено MiaoMiao: " + name + " [" + dev.getAddress() + "]");
                else Log.i(TAG, "Найдено MiaoMiao: " + name + " [" + dev.getAddress() + "] (без activity)");
                stopScanStatic();
                if (activity != null) activity.connectGattStatic(dev);
                else connectGattStaticInBackground(dev, context);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
            if (activity != null) activity.log("Ошибка сканирования BLE: " + errorCode);
            else Log.e(TAG, "Ошибка сканирования BLE: " + errorCode + " (без activity)");
            staticIsScanning = false;
            if (staticConnectedDevice == null && activity != null && activity.btnScan != null) {
                activity.runOnUiThread(() -> activity.btnScan.setEnabled(true));
            }
        }
    };

    private static final BluetoothGattCallback staticGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(@NonNull BluetoothGatt gatt, int status, int newState) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
            String deviceName = "";
            try {
                Context safeContext = getContextFromRef() != null ? getContextFromRef() : App.getAppContext();
                if (safeContext != null && (ActivityCompat.checkSelfPermission(safeContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    deviceName = gatt.getDevice().getName();
                } else {
                    deviceName = gatt.getDevice().getAddress();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting device name in onConnectionStateChange", e);
                deviceName = gatt.getDevice().getAddress();
            }


            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    String logMsg = "GATT подключен к " + deviceName + ", обнаружение сервисов...";
                    if (activity != null) activity.log(logMsg); else Log.i(TAG, logMsg);

                    staticBluetoothGatt = gatt;
                    boolean discoveryStarted = gatt.discoverServices();
                    if (!discoveryStarted) {
                        logMsg = "Не удалось запустить обнаружение сервисов для " + deviceName;
                        if (activity != null) activity.log(logMsg); else Log.e(TAG, logMsg);
                        if (activity != null) activity.handleStaticGattClose(); else handleStaticGattCloseInBackground();
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    String logMsg = "GATT отключен от " + deviceName;
                    if (activity != null) activity.log(logMsg); else Log.i(TAG, logMsg);
                    stopDataPolling();
                    if (activity != null) activity.runOnUiThread(activity::updateUiForDisconnectedState);
                }
            } else {
                String logMsg = "Ошибка состояния GATT для " + deviceName + ": " + status + ", newState: " + newState;
                if (activity != null) activity.log(logMsg); else Log.e(TAG, logMsg);
                stopDataPolling();
                if (activity != null) activity.handleStaticGattClose(); else handleStaticGattCloseInBackground();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(@NonNull BluetoothGatt gatt, int status) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
            String deviceName = "";
            try {
                Context safeContext = getContextFromRef() != null ? getContextFromRef() : App.getAppContext();
                if (safeContext != null && (ActivityCompat.checkSelfPermission(safeContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    deviceName = gatt.getDevice().getName();
                } else {
                    deviceName = gatt.getDevice().getAddress();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting device name in onServicesDiscovered", e);
                deviceName = gatt.getDevice().getAddress();
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                String logMsgBase = "Сервисы обнаружены для " + deviceName + ". ";
                if (activity != null) activity.log(logMsgBase + "Поиск Nordic UART..."); else Log.i(TAG, logMsgBase + "Поиск Nordic UART...");

                BluetoothGattService service = gatt.getService(UUID_NRF_DATA_SERVICE);
                if (service != null) {
                    if (activity != null) activity.log("Найден сервис Nordic UART."); else Log.i(TAG, "Найден сервис Nordic UART.");
                    BluetoothGattCharacteristic charForNotifications = service.getCharacteristic(UUID_CHARACTERISTIC_TX_TO_MIAOMIAO);
                    if (charForNotifications != null) {
                        if (gatt.setCharacteristicNotification(charForNotifications, true)) {
                            BluetoothGattDescriptor descriptor = charForNotifications.getDescriptor(UUID_CCCD);
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                                if (activity!=null) activity.log("Запрос на запись дескриптора ENABLE_NOTIFICATION_VALUE отправлен.");
                                else Log.i(TAG, "Запрос на запись дескриптора ENABLE_NOTIFICATION_VALUE отправлен.");
                            } else {
                                if (activity!=null) activity.log("Ошибка: CCCD дескриптор НЕ НАЙДЕН."); else Log.e(TAG, "Ошибка: CCCD дескриптор НЕ НАЙДЕН.");
                            }
                        } else {
                            if (activity!=null) activity.log("Ошибка: setCharacteristicNotification вернул false."); else Log.e(TAG, "Ошибка: setCharacteristicNotification вернул false.");
                        }
                    } else {
                        if (activity!=null) activity.log("Ошибка: Характеристика TX не найдена."); else Log.e(TAG, "Ошибка: Характеристика TX не найдена.");
                    }
                } else {
                    if (activity!=null) activity.log("Ошибка: сервис Nordic UART не найден."); else Log.e(TAG, "Ошибка: сервис Nordic UART не найден.");
                }
            } else {
                String logMsg = "Ошибка обнаружения сервисов для " + deviceName + ": " + status;
                if (activity != null) activity.log(logMsg); else Log.e(TAG, logMsg);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (UUID_CCCD.equals(descriptor.getUuid()) &&
                        UUID_CHARACTERISTIC_TX_TO_MIAOMIAO.equals(descriptor.getCharacteristic().getUuid())) {
                    String logMsg = "Уведомления для " + descriptor.getCharacteristic().getUuid().toString() + " успешно включены.";
                    if (activity != null) activity.log(logMsg); else Log.i(TAG, logMsg);

                    if (activity != null) {
                        activity.sendGeneralCommandStatic(new byte[]{CMD_START_DATA_STREAM}, "START_DATA_STREAM");
                    } else {
                        sendCommandWhenActivityUnavailable(new byte[]{CMD_START_DATA_STREAM}, "INITIAL_START_STREAM_NO_ACTIVITY");
                    }
                    startDataPolling();
                }
            } else {
                String logMsg = "Ошибка записи дескриптора: " + status;
                if (activity != null) activity.log(logMsg); else Log.e(TAG, logMsg);
            }
        }

        @Override
        public void onCharacteristicWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
            if (activity == null && !UUID_CHARACTERISTIC_RX_FROM_MIAOMIAO.equals(characteristic.getUuid())) return;

            byte[] value = characteristic.getValue();
            String commandName = "неизвестной команды";
            if (value != null && value.length > 0 && value[0] == CMD_START_DATA_STREAM) {
                commandName = "START_DATA_STREAM (0xF0)";
            } else if (value != null && value.length > 1 && value[0] == CMD_ALLOW_SENSOR[0] && value[1] == CMD_ALLOW_SENSOR[1]) {
                commandName = "ALLOW_SENSOR (" + (activity != null ? activity.bytesToHex(CMD_ALLOW_SENSOR) : bytesToHexStatic(CMD_ALLOW_SENSOR)) + ")";
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                String logMsg = "Команда " + commandName + " успешно отправлена.";
                if (activity != null) activity.log(logMsg); else Log.i(TAG, logMsg);
            } else {
                String logMsg = "Ошибка отправки " + commandName + ", статус: " + status;
                if (activity != null) activity.log(logMsg); else Log.e(TAG, logMsg);
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
            current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;

            if (UUID_CHARACTERISTIC_TX_TO_MIAOMIAO.equals(characteristic.getUuid())) {
                byte[] rawDataChunk = characteristic.getValue();
                if (rawDataChunk != null && rawDataChunk.length > 0) {
                    String hexData = activity != null ? activity.bytesToHex(rawDataChunk) : bytesToHexStatic(rawDataChunk);
                    String logMsgCommon = "Получен чанк данных (" + rawDataChunk.length + " байт): " + hexData;

                    if (activity != null && !activity.instanceFirstPacketDisplayed) {
                        activity.runOnUiThread(() -> activity.tvLog.setText(""));
                        activity.instanceFirstPacketDisplayed = true;
                        activity.log("Получен первый чанк данных (" + rawDataChunk.length + " байт): " + hexData);
                    } else if (activity != null) {
                        activity.log(logMsgCommon);
                    } else {
                        Log.i(TAG, logMsgCommon + " (без activity)");
                    }

                    synchronized (staticPacketBuffer) {
                        for (byte b : rawDataChunk) {
                            staticPacketBuffer.add(b);
                        }
                    }
                    if (activity != null) activity.processBufferedDataStatic();
                    else processBufferedDataStaticInBackground();
                }
            }
        }
    };
    // ================== КОНЕЦ СТАТИЧЕСКИХ КОЛБЭКОВ ==================

    private static Context getContextFromRef() {
        if (currentActivityInstanceRef != null && currentActivityInstanceRef.get() != null) {
            return currentActivityInstanceRef.get().getApplicationContext();
        }
        return App.getAppContext();
    }

    @SuppressLint("MissingPermission")
    private static void connectGattStaticInBackground(BluetoothDevice device, Context context) {
        Context appContext = context != null ? context.getApplicationContext() : App.getAppContext();
        if (appContext == null) {
            Log.e(TAG, "connectGattStaticInBackground: Context is null, cannot connect.");
            return;
        }
        String deviceAddress = "";
        try {
            deviceAddress = device.getAddress();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException for device.getAddress() in connectGattStaticInBackground: " + e.getMessage());
        }

        Log.i(TAG, "Подключение к GATT в фоновом режиме для " + deviceAddress);
        staticConnectedDevice = device;
        if (staticBluetoothGatt != null) {
            if (!staticBluetoothGatt.getDevice().getAddress().equals(device.getAddress())) {
                staticBluetoothGatt.close();
                staticBluetoothGatt = null;
            } else {
                return;
            }
        }
        synchronized (staticPacketBuffer) {
            staticPacketBuffer.clear();
        }
        staticLastReceivedPacket = null;
        staticBluetoothGatt = device.connectGatt(appContext, false, staticGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private static void handleStaticGattCloseInBackground() {
        Log.i(TAG, "Полное закрытие статического GATT соединения (в фоновом режиме)...");
        stopDataPolling();
        if (staticBluetoothGatt != null) {
            try {
                Context context = getContextFromRef();
                if (context != null && (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S )) {
                    staticBluetoothGatt.disconnect();
                    staticBluetoothGatt.close();
                } else if (context == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    staticBluetoothGatt.disconnect();
                    staticBluetoothGatt.close();
                } else {
                    Log.w(TAG, "Cannot close GATT in background due to missing permission or context.");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException on gatt close in background: " + e.getMessage());
            } finally {
                staticBluetoothGatt = null;
            }
        }
        staticConnectedDevice = null;
        staticIsScanning = false;
        synchronized (staticPacketBuffer) {
            staticPacketBuffer.clear();
        }
        staticLastReceivedPacket = null;
    }


    private static void processBufferedDataStaticInBackground() {
        synchronized (staticPacketBuffer) {
            while (staticPacketBuffer.size() >= 1) {
                byte firstByte = staticPacketBuffer.get(0);
                if (firstByte == MiaoMiaoPacket.START_PKT) {
                    if (staticPacketBuffer.size() < 3) break;
                    ByteBuffer bbLength = ByteBuffer.wrap(new byte[]{staticPacketBuffer.get(1), staticPacketBuffer.get(2)}).order(ByteOrder.BIG_ENDIAN);
                    int expectedLength = bbLength.getShort();
                    if (expectedLength <= 0 || expectedLength > 1024) {
                        staticPacketBuffer.remove(0); continue;
                    }
                    if (staticPacketBuffer.size() >= expectedLength) {
                        byte[] fullPacketBytes = new byte[expectedLength];
                        for (int i = 0; i < expectedLength; i++) fullPacketBytes[i] = staticPacketBuffer.get(i);
                        for (int i = 0; i < expectedLength; i++) staticPacketBuffer.remove(0);
                        try {
                            MiaoMiaoPacket pkt = MiaoMiaoPacket.fromBytes(fullPacketBytes);
                            Log.i(TAG, "Background: Успешно разобран пакет: " + pkt.toString());
                            staticLastReceivedPacket = pkt;

                            Context appContext = getContextFromRef();
                            if (appContext != null && pkt.librePacket != null) {
                                float rawPacketValue = -1.0f;
                                if (pkt.librePacket.trends != null && !pkt.librePacket.trends.isEmpty()) {
                                    float[] latestTrend = pkt.librePacket.trends.get(0);
                                    if (latestTrend != null && latestTrend.length > 0) rawPacketValue = latestTrend[0];
                                } else if (pkt.librePacket.history != null && !pkt.librePacket.history.isEmpty()) {
                                    float[] latestHistory = pkt.librePacket.history.get(0);
                                    if (latestHistory != null && latestHistory.length > 0) rawPacketValue = latestHistory[0];
                                }
                                if (rawPacketValue != -1.0f) {
                                    float glucoseRawMmolL = rawPacketValue / MG_DL_TO_MMOL_L_FACTOR;
                                    SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                    float correction = prefs.getFloat(CORRECTION_KEY, 0.0f);
                                    float correctedGlucoseMmolL = glucoseRawMmolL + correction;

                                    Intent intent = new Intent(ACTION_GLUCOSE_UPDATE);
                                    intent.putExtra(EXTRA_GLUCOSE_VALUE, correctedGlucoseMmolL);
                                    LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
                                    Log.i(TAG, "Background: Отправлен broadcast с correctedGlucoseMmolL: " + correctedGlucoseMmolL);
                                    appendDataToHistoryFileInBackground(appContext, correctedGlucoseMmolL);
                                    showGlucoseNotification(appContext, "Глюкоза: " + String.format(Locale.US, "%.1f", correctedGlucoseMmolL) + " ммоль/л");
                                }
                            }

                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Background: Ошибка парсинга MiaoMiaoPacket: " + e.getMessage());
                        }
                    } else break;
                } else if (firstByte == MiaoMiaoPacket.NEW_SENSOR || firstByte == MiaoMiaoPacket.NO_SENSOR) {
                    byte statusByte = staticPacketBuffer.remove(0);
                    String statusMsg = (firstByte == MiaoMiaoPacket.NEW_SENSOR) ? "Новый сенсор" : "Нет сенсора";
                    Log.i(TAG, "Background: Получен статус: " + statusMsg + String.format(" (0x%02X)", statusByte));
                    Context appContext = getContextFromRef();
                    if (appContext != null) {
                        showGlucoseNotification(appContext, "Статус сенсора: " + statusMsg);
                    }
                    if (firstByte == MiaoMiaoPacket.NEW_SENSOR) {
                        sendCommandWhenActivityUnavailable(CMD_ALLOW_SENSOR, "ALLOW_SENSOR_NO_ACTIVITY");
                    }
                } else {
                    staticPacketBuffer.remove(0);
                }
            }
        }
    }
    private static void appendDataToHistoryFileInBackground(Context context, float glucoseValue) {
        if (context == null) {
            Log.e(TAG_FILE_LOG, "Контекст null, не могу записать в файл истории (фон).");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG_FILE_LOG, "Нет разрешения на запись для сохранения истории (фон).");
            return;
        }

        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.e(TAG_FILE_LOG, "Внешнее хранилище недоступно (фон): " + state);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = timestamp + " " + String.format(Locale.US, "%.1f", glucoseValue) + "\n";

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                Log.e(TAG_FILE_LOG, "Не удалось создать директорию Download (фон).");
                return;
            }
        }
        File historyFile = new File(downloadsDir, HISTORY_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(historyFile, true)) {
            fos.write(logEntry.getBytes());
            Log.d(TAG_FILE_LOG, "Запись добавлена в " + historyFile.getAbsolutePath() + " (фон): " + logEntry.trim());
        } catch (IOException e) {
            Log.e(TAG_FILE_LOG, "Ошибка записи в файл " + historyFile.getAbsolutePath() + " (фон)", e);
        }
    }

    @SuppressLint("MissingPermission")
    private static void startDataPolling() {
        current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;

        if (staticBluetoothGatt == null || staticConnectedDevice == null) {
            String msg = "Не могу начать опрос: нет активного соединения.";
            if (activity != null) activity.log(msg); else Log.w(TAG, msg);
            isPollingScheduled = false;
            return;
        }

        if (isPollingScheduled) {
            String msg = "Опрос уже запланирован.";
            if (activity != null) activity.log(msg); else Log.i(TAG, msg);
            return;
        }

        if (staticPollingRunnable == null) {
            staticPollingRunnable = new Runnable() {
                @Override
                public void run() {
                    current_data currentActivity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
                    if (staticBluetoothGatt != null && staticConnectedDevice != null) {
                        String logMsg = "Выполняется периодический опрос датчика...";
                        if (currentActivity != null) {
                            currentActivity.log(logMsg);
                            currentActivity.sendGeneralCommandStatic(new byte[]{CMD_START_DATA_STREAM}, "PERIODIC_POLL_START_STREAM");
                        } else {
                            Log.i(TAG, logMsg + " (без activity)");
                            sendCommandWhenActivityUnavailable(new byte[]{CMD_START_DATA_STREAM}, "PERIODIC_POLL_NO_ACTIVITY");
                        }

                        if (staticMainHandler != null && staticPollingRunnable != null && isPollingScheduled) {
                            staticMainHandler.postDelayed(this, POLLING_INTERVAL);
                            Log.i(TAG, "Следующий опрос через " + (POLLING_INTERVAL / (60 * 1000)) + " минут.");
                        } else {
                            isPollingScheduled = false;
                            Log.i(TAG, "Повторный запуск опроса отменен (handler/runnable null или опрос остановлен).");
                        }
                    } else {
                        Log.w(TAG, "Опрос остановлен: нет соединения.");
                        stopDataPolling();
                    }
                }
            };
        }

        if(staticMainHandler != null) {
            staticMainHandler.removeCallbacks(staticPollingRunnable);
            staticMainHandler.postDelayed(staticPollingRunnable, POLLING_INTERVAL);
            isPollingScheduled = true;
            String msg = "Автоматический опрос датчика запущен (каждые " + (POLLING_INTERVAL / (60 * 1000)) + " минут).";
            if (activity != null) activity.log(msg); else Log.i(TAG, msg);
        } else {
            Log.e(TAG, "staticMainHandler не инициализирован, опрос не может быть запущен.");
            isPollingScheduled = false;
        }
    }

    private static void stopDataPolling() {
        current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
        if (staticMainHandler != null && staticPollingRunnable != null) {
            staticMainHandler.removeCallbacks(staticPollingRunnable);
        }
        isPollingScheduled = false;
        String msg = "Автоматический опрос датчика остановлен.";
        if (activity != null) activity.log(msg); else Log.i(TAG, msg);
    }

    @SuppressLint("MissingPermission")
    private static void sendCommandWhenActivityUnavailable(byte[] commandPayload, String commandName) {
        if (staticBluetoothGatt == null) {
            Log.w(TAG, "sendCommandWhenActivityUnavailable (" + commandName + "): staticBluetoothGatt не подключен.");
            return;
        }
        Context context = getContextFromRef();
        if (context != null && !(ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
            Log.w(TAG, "sendCommandWhenActivityUnavailable (" + commandName + "): BLUETOOTH_CONNECT permission missing.");
            return;
        }


        BluetoothGattService service = staticBluetoothGatt.getService(UUID_NRF_DATA_SERVICE);
        if (service != null) {
            BluetoothGattCharacteristic commandChar = service.getCharacteristic(UUID_CHARACTERISTIC_RX_FROM_MIAOMIAO);
            if (commandChar != null) {
                Log.i(TAG, "Фоновая команда: Отправка " + commandName + " (" + bytesToHexStatic(commandPayload) + ")");
                commandChar.setValue(commandPayload);
                commandChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (!staticBluetoothGatt.writeCharacteristic(commandChar)) {
                    Log.e(TAG, "Фоновая команда: Ошибка отправки " + commandName + ".");
                } else {
                    Log.i(TAG, "Фоновая команда: Запрос на отправку " + commandName + " отправлен.");
                }
            } else {
                Log.e(TAG, "Фоновая команда: Характеристика для " + commandName + " не найдена.");
            }
        } else {
            Log.e(TAG, "Фоновая команда: Сервис Nordic UART не найден для " + commandName + ".");
        }
    }

    private static String bytesToHexStatic(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }


    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_current_data);

        currentActivityInstanceRef = new WeakReference<>(this);
        if (staticMainHandler == null) {
            staticMainHandler = new Handler(Looper.getMainLooper());
        }
        App.init(getApplicationContext()); // Инициализация App контекста
        createNotificationChannel(); // Создание канала уведомлений


        spinner = findViewById(R.id.spinner2);
        tvLog = findViewById(R.id.tv_status);
        btnScan = findViewById(R.id.btn_scan);
        tvConnectedDeviceHeader = findViewById(R.id.tv_connected_device_header);
        tvDeviceName = findViewById(R.id.tv_device_name);
        tvBattery = findViewById(R.id.tv_battery);
        tvGlucoseData = findViewById(R.id.tv_glucose_data);
        tvFirmwareVersion = findViewById(R.id.tv_firmware_version);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        tvCurrentCorrectionLabel = findViewById(R.id.tv_current_correction_label);
        tvCurrentCorrection = findViewById(R.id.tv_current_correction);
        etCorrectionValue = findViewById(R.id.et_correction_value);
        btnApplyCorrection = findViewById(R.id.btn_apply_correction);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentGlucoseCorrection = prefs.getFloat(CORRECTION_KEY, 0.0f);

        btnScan.setOnClickListener(v -> {
            tvLog.setText("");
            log("Статус: Ожидание");
            resetUiBeforeScan();
            checkAndRequestPermissions(); // Запрос всех разрешений, включая уведомления
        });

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
                    case 0: break;
                    case 1: activityClass = com.example.miaotrack.MainActivity.class; break;
                    case 2: activityClass = com.example.miaotrack.history_measure.class; break;
                }
                if (activityClass != null) {
                    startActivity(new Intent(current_data.this, activityClass));
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        });

        btnDisconnect.setOnClickListener(v -> handleStaticGattClose());

        btnApplyCorrection.setOnClickListener(v -> {
            try {
                String correctionStr = etCorrectionValue.getText().toString().replace(',', '.');
                currentGlucoseCorrection = correctionStr.isEmpty() ? 0.0f : Float.parseFloat(correctionStr);

                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.putFloat(CORRECTION_KEY, currentGlucoseCorrection);
                editor.apply();
                updateCurrentCorrectionDisplay();
                etCorrectionValue.setText("");
                Toast.makeText(current_data.this, "Коррекция применена: " + String.format(Locale.US, "%.1f", currentGlucoseCorrection), Toast.LENGTH_SHORT).show();

                if (staticLastReceivedPacket != null) {
                    updateUiOnlyWithPacketData(staticLastReceivedPacket);
                    // Показываем уведомление с новым скорректированным значением
                    float rawPacketValue = -1.0f;
                    if (staticLastReceivedPacket.librePacket != null) {
                        if (staticLastReceivedPacket.librePacket.trends != null && !staticLastReceivedPacket.librePacket.trends.isEmpty()) {
                            float[] latestTrend = staticLastReceivedPacket.librePacket.trends.get(0);
                            if (latestTrend != null && latestTrend.length > 0) rawPacketValue = latestTrend[0];
                        } else if (staticLastReceivedPacket.librePacket.history != null && !staticLastReceivedPacket.librePacket.history.isEmpty()) {
                            float[] latestHistory = staticLastReceivedPacket.librePacket.history.get(0);
                            if (latestHistory != null && latestHistory.length > 0) rawPacketValue = latestHistory[0];
                        }
                    }
                    if (rawPacketValue != -1.0f) {
                        float glucoseRawMmolL = rawPacketValue / MG_DL_TO_MMOL_L_FACTOR;
                        float correctedGlucoseMmolL = glucoseRawMmolL + currentGlucoseCorrection; // Новая коррекция
                        if (correctedGlucoseMmolL>=10||correctedGlucoseMmolL<=4) {
                            showGlucoseNotification(getApplicationContext(), "Высокий или низкий сахар! (скорр.): " + String.format(Locale.US, "%.1f", correctedGlucoseMmolL) + " ммоль/л");
                        }
                        // Запись в историю этого скорректированного значения
                        appendDataToHistoryFile(correctedGlucoseMmolL);
                    }
                }
            } catch (NumberFormatException e) {
                Toast.makeText(current_data.this, "Ошибка: некорректное значение коррекции", Toast.LENGTH_SHORT).show();
                log("Ошибка: некорректное значение коррекции - " + etCorrectionValue.getText().toString());
            }
        });

        if (staticBluetoothAdapter == null) {
            BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (btManager != null) {
                staticBluetoothAdapter = btManager.getAdapter();
            }
        }

        if (staticBluetoothAdapter == null) {
            log("Bluetooth не поддерживается на этом устройстве.");
            if (btnScan != null) btnScan.setEnabled(false);
        }
    }

    private void updateCurrentCorrectionDisplay() {
        runOnUiThread(()-> {
            if (tvCurrentCorrection != null) tvCurrentCorrection.setText(String.format(Locale.US, "%.1f", currentGlucoseCorrection));
        });
    }

    @SuppressLint("MissingPermission")
    private void updateUiOnlyWithPacketData(MiaoMiaoPacket packet) {
        runOnUiThread(() -> {
            if (packet == null) {
                Log.w(TAG, "updateUiOnlyWithPacketData: packet is null");
                return;
            }

            if (staticConnectedDevice != null) {
                String deviceNameStr = null;
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    deviceNameStr = staticConnectedDevice.getName();
                } else {
                    deviceNameStr = staticConnectedDevice.getAddress();
                }
                if (tvDeviceName != null) tvDeviceName.setText("Устройство: " + (deviceNameStr != null && !deviceNameStr.isEmpty() ? deviceNameStr : staticConnectedDevice.getAddress()));
            } else {
                if (tvDeviceName != null) tvDeviceName.setText("Устройство: -");
            }

            if (tvBattery != null) tvBattery.setText("Батарея: " + packet.battery + "%");
            if (tvFirmwareVersion != null) tvFirmwareVersion.setText("Прошивка: v" + Integer.toHexString(packet.fwVersion).toUpperCase());

            float rawPacketValue = -1.0f;
            String sourceLabel = "";

            if (packet.librePacket != null) {
                if (packet.librePacket.trends != null && !packet.librePacket.trends.isEmpty()) {
                    float[] latestTrend = packet.librePacket.trends.get(0);
                    if (latestTrend != null && latestTrend.length > 0) rawPacketValue = latestTrend[0];
                } else if (packet.librePacket.history != null && !packet.librePacket.history.isEmpty()) {
                    float[] latestHistory = packet.librePacket.history.get(0);
                    if (latestHistory != null && latestHistory.length > 0) {
                        rawPacketValue = latestHistory[0];
                        sourceLabel = " (Ист.)";
                    }
                }
            }


            if (rawPacketValue != -1.0f) {
                float glucoseRawMmolL = rawPacketValue / MG_DL_TO_MMOL_L_FACTOR;
                float correctedGlucoseMmolL = glucoseRawMmolL + currentGlucoseCorrection;
                if (tvGlucoseData != null) tvGlucoseData.setText(String.format(Locale.US, "%.1f mmol/L", correctedGlucoseMmolL) + sourceLabel);
            } else {
                if (tvGlucoseData != null && (tvGlucoseData.getText().toString().isEmpty() || !tvGlucoseData.getText().toString().startsWith("Статус:"))) {
                    tvGlucoseData.setText("Данные глюкозы: -");
                }
            }

            if (staticConnectedDevice != null) {
                updateUiForConnectedDevice(staticConnectedDevice);
            }
            if (tvBattery != null) tvBattery.setVisibility(View.VISIBLE);
            if (tvFirmwareVersion != null) tvFirmwareVersion.setVisibility(View.VISIBLE);
            if (tvGlucoseData != null) tvGlucoseData.setVisibility(View.VISIBLE);
        });
    }


    @Override
    protected void onResume() {
        super.onResume();
        currentActivityInstanceRef = new WeakReference<>(this);

        if (staticBluetoothAdapter != null && !staticBluetoothAdapter.isEnabled()) {
            log("Bluetooth выключен. Пожалуйста, включите Bluetooth.");
            return;
        }

        if (staticConnectedDevice != null && staticBluetoothGatt != null) {
            String deviceName = "";
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                deviceName = staticConnectedDevice.getName();
            } else {
                deviceName = staticConnectedDevice.getAddress();
            }
            log("Восстановление UI для существующего соединения с " + deviceName);
            updateUiForConnectedDevice(staticConnectedDevice);
            if (staticLastReceivedPacket != null) {
                updateUiOnlyWithPacketData(staticLastReceivedPacket);
            }
        } else {
            resetConnectedDeviceUiOnly();
        }
        if (spinner != null) {
            spinner.setTag(spinner.getSelectedItemPosition());
            spinner.setSelection(0, false);
        }
        updateCurrentCorrectionDisplay();
    }

    private void log(String message) {
        Log.d(TAG, message);
        current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : this;
        if (activity != null && activity.tvLog != null) {
            activity.runOnUiThread(() -> {
                if (activity.tvLog.getLineCount() > 200) {
                    CharSequence currentText = activity.tvLog.getText();
                    activity.tvLog.setText(currentText.subSequence(currentText.length() / 2, currentText.length()));
                }
                activity.tvLog.append(message + "\n");
            });
        }
    }


    @SuppressLint("MissingPermission")
    private void startScanStatic() {
        if (!hasRequiredBluetoothPermissions()) {
            log("Отсутствуют необходимые Bluetooth разрешения для сканирования.");
            checkAndRequestPermissions();
            return;
        }
        if (staticIsScanning) {
            log("Сканирование уже запущено.");
            return;
        }
        if (staticBleScanner == null && staticBluetoothAdapter != null) {
            staticBleScanner = staticBluetoothAdapter.getBluetoothLeScanner();
        }
        if (staticBleScanner == null) {
            log("Не удалось получить BluetoothLeScanner.");
            return;
        }

        log("Старт BLE сканирования...");
        synchronized (staticPacketBuffer) {
            staticPacketBuffer.clear();
        }
        staticIsScanning = true;
        if (btnScan != null) runOnUiThread(() -> btnScan.setEnabled(false));

        if (staticMainHandler != null) {
            staticMainHandler.postDelayed(() -> {
                if (staticIsScanning) {
                    log("Остановка сканирования (тайм-аут)");
                    stopScanStatic();
                }
            }, SCAN_PERIOD);
            staticBleScanner.startScan(staticScanCallback);
        } else {
            log("Ошибка: staticMainHandler не инициализирован, сканирование не может быть запущено с таймаутом.");
            staticIsScanning = false;
            if (btnScan != null) runOnUiThread(() -> btnScan.setEnabled(true));
        }
    }

    @SuppressLint("MissingPermission")
    private static void stopScanStatic() {
        Context context = getContextFromRef();
        if (staticBleScanner != null && staticIsScanning && staticBluetoothAdapter != null && staticBluetoothAdapter.isEnabled()) {
            try {
                if (context != null && (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    staticBleScanner.stopScan(staticScanCallback);
                } else if (context == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S){
                    staticBleScanner.stopScan(staticScanCallback);
                } else {
                    Log.w(TAG, "Cannot stop scan due to missing BLUETOOTH_SCAN permission or context.");
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Ошибка при остановке сканирования: Bluetooth выключен?", e);
            }  catch (SecurityException se) {
                Log.e(TAG, "SecurityException при остановке сканирования: " + se.getMessage());
            }
        }
        staticIsScanning = false;
        current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
        if (staticConnectedDevice == null && activity != null && activity.btnScan != null) {
            activity.runOnUiThread(() -> activity.btnScan.setEnabled(true));
        }
    }

    @SuppressLint("MissingPermission")
    private void connectGattStatic(BluetoothDevice device) {
        log("Подключение к GATT для " + device.getAddress());
        instanceFirstPacketDisplayed = false;
        staticConnectedDevice = device;

        if (staticBluetoothGatt != null) {
            if (!staticBluetoothGatt.getDevice().getAddress().equals(device.getAddress())) {
                staticBluetoothGatt.close();
                staticBluetoothGatt = null;
            } else {
                updateUiForConnectedDevice(device);
                return;
            }
        }
        synchronized (staticPacketBuffer) {
            staticPacketBuffer.clear();
        }
        staticLastReceivedPacket = null;
        updateUiForConnectedDevice(device);
        staticBluetoothGatt = device.connectGatt(getApplicationContext(), false, staticGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void updateUiForConnectedDevice(BluetoothDevice device) {
        runOnUiThread(() -> {
            String deviceNameStr = null;
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                deviceNameStr = device.getName();
            } else {
                deviceNameStr = device.getAddress();
            }
            if (tvDeviceName != null) tvDeviceName.setText("Устройство: " + (deviceNameStr != null && !deviceNameStr.isEmpty() ? deviceNameStr : device.getAddress()));
            if (tvConnectedDeviceHeader!=null) tvConnectedDeviceHeader.setVisibility(View.VISIBLE);
            if (tvDeviceName!=null) tvDeviceName.setVisibility(View.VISIBLE);
            if (btnDisconnect!=null) btnDisconnect.setVisibility(View.VISIBLE);
            if (btnScan!=null) btnScan.setVisibility(View.GONE);

            if (tvCurrentCorrectionLabel!=null) tvCurrentCorrectionLabel.setVisibility(View.VISIBLE);
            if (tvCurrentCorrection!=null) tvCurrentCorrection.setVisibility(View.VISIBLE);
            if (etCorrectionValue!=null) etCorrectionValue.setVisibility(View.VISIBLE);
            if (btnApplyCorrection!=null) btnApplyCorrection.setVisibility(View.VISIBLE);
            updateCurrentCorrectionDisplay();
        });
    }

    private void updateUiForDisconnectedState() {
        runOnUiThread(() -> {
            resetConnectedDeviceUiOnly();
            log("Соединение потеряно или не установлено.");
        });
    }

    @SuppressLint("MissingPermission")
    private void sendGeneralCommandStatic(@NonNull byte[] commandPayload, String commandName) {
        if (staticBluetoothGatt == null) {
            log("sendGeneralCommand ("+commandName+"): staticBluetoothGatt не подключен.");
            return;
        }
        if (!(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
            log("sendGeneralCommand ("+commandName+"): BLUETOOTH_CONNECT permission missing.");
            return;
        }

        BluetoothGattService service = staticBluetoothGatt.getService(UUID_NRF_DATA_SERVICE);
        if (service != null) {
            BluetoothGattCharacteristic commandChar = service.getCharacteristic(UUID_CHARACTERISTIC_RX_FROM_MIAOMIAO);
            if (commandChar != null) {
                log("Отправка команды " + commandName + " (" + bytesToHex(commandPayload) + ") на " + commandChar.getUuid().toString());
                commandChar.setValue(commandPayload);
                commandChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (!staticBluetoothGatt.writeCharacteristic(commandChar)) {
                    log("Ошибка: не удалось инициировать отправку команды " + commandName + ".");
                } else {
                    log("Запрос на отправку команды " + commandName + " отправлен.");
                }
            } else {
                log("Ошибка: Характеристика (" + UUID_CHARACTERISTIC_RX_FROM_MIAOMIAO.toString() + ") для команды " + commandName + " не найдена.");
            }
        } else {
            log("Ошибка: сервис Nordic UART не найден для команды " + commandName + ".");
        }
    }

    private void processBufferedDataStatic() {
        synchronized (staticPacketBuffer) {
            while (staticPacketBuffer.size() >= 1) {
                byte firstByte = staticPacketBuffer.get(0);

                if (firstByte == MiaoMiaoPacket.START_PKT) {
                    if (staticPacketBuffer.size() < 3) {
                        log("В буфере START_PKT, но нет данных для длины. Ожидание...");
                        break;
                    }
                    ByteBuffer bbLength = ByteBuffer.wrap(new byte[]{staticPacketBuffer.get(1), staticPacketBuffer.get(2)}).order(ByteOrder.BIG_ENDIAN);
                    int expectedLength = bbLength.getShort();
                    if (expectedLength <= 0 || expectedLength > 1024) {
                        log("Некорректная длина пакета: " + expectedLength + ". Удаление START_PKT.");
                        staticPacketBuffer.remove(0);
                        continue;
                    }

                    if (staticPacketBuffer.size() >= expectedLength) {
                        byte[] fullPacketBytes = new byte[expectedLength];
                        for (int i = 0; i < expectedLength; i++) {
                            fullPacketBytes[i] = staticPacketBuffer.get(i);
                        }
                        for (int i = 0; i < expectedLength; i++) {
                            staticPacketBuffer.remove(0);
                        }
                        log("Собран полный пакет (" + fullPacketBytes.length + " байт). Парсинг...");
                        try {
                            MiaoMiaoPacket pkt = MiaoMiaoPacket.fromBytes(fullPacketBytes);
                            log("Успешно разобран пакет: " + pkt.toString());
                            staticLastReceivedPacket = pkt;
                            updateUiWithPacketData(pkt);
                        } catch (IllegalArgumentException e) {
                            log("Ошибка парсинга MiaoMiaoPacket: " + e.getMessage() + " для данных: " + bytesToHex(fullPacketBytes));
                        }
                    } else {
                        log("В буфере " + staticPacketBuffer.size() + " байт (ожидается " + expectedLength + "). Ожидание...");
                        break;
                    }
                } else if (firstByte == MiaoMiaoPacket.NEW_SENSOR || firstByte == MiaoMiaoPacket.NO_SENSOR) {
                    byte statusByte = staticPacketBuffer.remove(0);
                    String statusMsg = (firstByte == MiaoMiaoPacket.NEW_SENSOR) ? "Новый сенсор" : "Нет сенсора";
                    log("Получен статус: " + statusMsg + String.format(" (0x%02X)", statusByte));
                    final String finalStatusMsg = statusMsg;
                    runOnUiThread(() -> {
                        if (tvGlucoseData != null) {
                            tvGlucoseData.setText("Статус: " + finalStatusMsg);
                            tvGlucoseData.setVisibility(View.VISIBLE);
                        }
                        if (tvConnectedDeviceHeader != null && tvConnectedDeviceHeader.getVisibility() == View.GONE && staticConnectedDevice != null) {
                            updateUiForConnectedDevice(staticConnectedDevice);
                        }
                    });
                    showGlucoseNotification(getApplicationContext(), "Статус сенсора: " + statusMsg);
                    if (firstByte == MiaoMiaoPacket.NEW_SENSOR) {
                        sendGeneralCommandStatic(CMD_ALLOW_SENSOR, "ALLOW_SENSOR");
                    }
                }
                else {
                    byte removedByte = staticPacketBuffer.remove(0);
                    log("Неизвестный байт в начале буфера: " + String.format("0x%02X", removedByte) + ". Удален.");
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void updateUiWithPacketData(MiaoMiaoPacket packet) {
        runOnUiThread(() -> {
            if (packet == null) {
                Log.w(TAG, "updateUiWithPacketData: packet is null");
                return;
            }
            if (staticConnectedDevice != null) {
                String deviceNameStr = null;
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    deviceNameStr = staticConnectedDevice.getName();
                } else {
                    deviceNameStr = staticConnectedDevice.getAddress();
                }
                if (tvDeviceName != null) tvDeviceName.setText("Устройство: " + (deviceNameStr != null && !deviceNameStr.isEmpty() ? deviceNameStr : staticConnectedDevice.getAddress()));
            } else {
                if (tvDeviceName != null) tvDeviceName.setText("Устройство: -");
            }
            if (tvBattery != null) tvBattery.setText("Батарея: " + packet.battery + "%");
            if (tvFirmwareVersion != null) tvFirmwareVersion.setText("Прошивка: v" + Integer.toHexString(packet.fwVersion).toUpperCase());

            float rawPacketValue = -1.0f;
            String sourceLabel = "";

            if (packet.librePacket != null) {
                if (packet.librePacket.trends != null && !packet.librePacket.trends.isEmpty()) {
                    float[] latestTrend = packet.librePacket.trends.get(0);
                    if (latestTrend != null && latestTrend.length > 0) rawPacketValue = latestTrend[0];
                } else if (packet.librePacket.history != null && !packet.librePacket.history.isEmpty()) {
                    float[] latestHistory = packet.librePacket.history.get(0);
                    if (latestHistory != null && latestHistory.length > 0) {
                        rawPacketValue = latestHistory[0];
                        sourceLabel = " (Ист.)";
                    }
                }
            }


            if (rawPacketValue != -1.0f) {
                float glucoseRawMmolL = rawPacketValue / MG_DL_TO_MMOL_L_FACTOR;
                float correctedGlucoseMmolL = glucoseRawMmolL + currentGlucoseCorrection;
                if (tvGlucoseData != null) tvGlucoseData.setText(String.format(Locale.US, "%.1f mmol/L", correctedGlucoseMmolL) + sourceLabel);

                Intent intent = new Intent(ACTION_GLUCOSE_UPDATE);
                intent.putExtra(EXTRA_GLUCOSE_VALUE, correctedGlucoseMmolL);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                log("Отправлен broadcast с correctedGlucoseMmolL: " + correctedGlucoseMmolL);

                appendDataToHistoryFile(correctedGlucoseMmolL);
                showGlucoseNotification(this, "Глюкоза: " + String.format(Locale.US, "%.1f", correctedGlucoseMmolL) + " ммоль/л");

            } else {
                if (tvGlucoseData != null && (tvGlucoseData.getText().toString().isEmpty() || !tvGlucoseData.getText().toString().startsWith("Статус:"))) {
                    tvGlucoseData.setText("Данные глюкозы: -");
                }
            }

            if (staticConnectedDevice != null) {
                updateUiForConnectedDevice(staticConnectedDevice);
            }
            if (tvBattery != null) tvBattery.setVisibility(View.VISIBLE);
            if (tvFirmwareVersion != null) tvFirmwareVersion.setVisibility(View.VISIBLE);
            if (tvGlucoseData != null) tvGlucoseData.setVisibility(View.VISIBLE);
        });
    }

    private void appendDataToHistoryFile(float glucoseValue) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)!= PackageManager.PERMISSION_GRANTED) {
            log("Нет разрешения на запись для сохранения истории. Запросите разрешение.");
            return;
        }

        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            log("Внешнее хранилище недоступно: " + state);
            Toast.makeText(this, "Внешнее хранилище недоступно.", Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH-mm-ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = timestamp + " " + String.format(Locale.US, "%.1f", glucoseValue) + "\n";

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                Log.e(TAG_FILE_LOG, "Не удалось создать директорию Download.");
                Toast.makeText(this, "Не удалось создать директорию Download.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        File historyFile = new File(downloadsDir, HISTORY_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(historyFile, true)) {
            fos.write(logEntry.getBytes());
            Log.d(TAG_FILE_LOG, "Запись добавлена в " + historyFile.getAbsolutePath() + ": " + logEntry.trim());
        } catch (IOException e) {
            Log.e(TAG_FILE_LOG, "Ошибка записи в файл " + historyFile.getAbsolutePath(), e);
            Toast.makeText(this, "Ошибка сохранения истории в файл.", Toast.LENGTH_SHORT).show();
        }
    }


    private void resetConnectedDeviceUiOnly() {
        runOnUiThread(() -> {
            if (tvConnectedDeviceHeader!=null) tvConnectedDeviceHeader.setVisibility(View.GONE);
            if (tvDeviceName!=null) {
                tvDeviceName.setVisibility(View.GONE);
                tvDeviceName.setText("Устройство: -");
            }
            if (tvBattery!=null) {
                tvBattery.setVisibility(View.GONE);
                tvBattery.setText("Батарея: -");
            }
            if (tvFirmwareVersion!=null) {
                tvFirmwareVersion.setVisibility(View.GONE);
                tvFirmwareVersion.setText("Прошивка: -");
            }
            if (tvGlucoseData!=null) {
                tvGlucoseData.setVisibility(View.GONE);
                tvGlucoseData.setText("Данные глюкозы: -");
            }
            if (btnDisconnect!=null) btnDisconnect.setVisibility(View.GONE);
            if (tvCurrentCorrectionLabel!=null) tvCurrentCorrectionLabel.setVisibility(View.GONE);
            if (tvCurrentCorrection!=null) tvCurrentCorrection.setVisibility(View.GONE);
            if (etCorrectionValue!=null) etCorrectionValue.setVisibility(View.GONE);
            if (btnApplyCorrection!=null) btnApplyCorrection.setVisibility(View.GONE);

            if (btnScan!=null) {
                btnScan.setVisibility(View.VISIBLE);
                btnScan.setEnabled(true);
            }
        });
        this.instanceFirstPacketDisplayed = false;
    }

    private void resetUiBeforeScan() {
        runOnUiThread(() -> {
            if (tvLog!=null) tvLog.setText("");
            if (tvConnectedDeviceHeader!=null) tvConnectedDeviceHeader.setVisibility(View.GONE);
            if (tvDeviceName!=null) tvDeviceName.setVisibility(View.GONE);
            if (tvBattery!=null) tvBattery.setVisibility(View.GONE);
            if (tvFirmwareVersion!=null) tvFirmwareVersion.setVisibility(View.GONE);
            if (tvGlucoseData!=null) tvGlucoseData.setVisibility(View.GONE);
            if (btnDisconnect!=null) btnDisconnect.setVisibility(View.GONE);
            if (tvCurrentCorrectionLabel!=null) tvCurrentCorrectionLabel.setVisibility(View.GONE);
            if (tvCurrentCorrection!=null) tvCurrentCorrection.setVisibility(View.GONE);
            if (etCorrectionValue!=null) etCorrectionValue.setVisibility(View.GONE);
            if (btnApplyCorrection!=null) btnApplyCorrection.setVisibility(View.GONE);
            if (btnScan!=null) btnScan.setEnabled(true);
        });
        this.instanceFirstPacketDisplayed = false;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }


    private boolean hasRequiredBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }


        if (!permissionsToRequest.isEmpty()) {
            log("Запрос разрешений: " + Arrays.toString(permissionsToRequest.toArray()));
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        } else {
            log("Все необходимые разрешения уже есть.");
            if (staticBluetoothAdapter != null && staticBluetoothAdapter.isEnabled() && staticConnectedDevice == null && !staticIsScanning) {
                startScanStatic();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allRequiredBluetoothPermissionsGranted = true;
            boolean writeStoragePermissionGranted = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            boolean postNotificationsPermissionGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);


            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.BLUETOOTH_SCAN.equals(permissions[i]) ||
                        Manifest.permission.BLUETOOTH_CONNECT.equals(permissions[i]) ||
                        Manifest.permission.BLUETOOTH.equals(permissions[i]) ||
                        Manifest.permission.BLUETOOTH_ADMIN.equals(permissions[i]) ||
                        Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        allRequiredBluetoothPermissionsGranted = false;
                        log("Разрешение Bluetooth/Location не предоставлено: " + permissions[i]);
                    }
                }
                if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        writeStoragePermissionGranted = true;
                        log("Разрешение на запись в хранилище предоставлено.");
                    } else {
                        log("Разрешение на запись в хранилище НЕ предоставлено.");
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        postNotificationsPermissionGranted = true;
                        log("Разрешение POST_NOTIFICATIONS предоставлено.");
                    } else {
                        log("Разрешение POST_NOTIFICATIONS НЕ предоставлено.");
                    }
                }
            }

            if (allRequiredBluetoothPermissionsGranted) {
                log("Основные разрешения (Bluetooth/Location) получены.");
                if (staticBluetoothAdapter != null && staticBluetoothAdapter.isEnabled() && staticConnectedDevice == null && !staticIsScanning) {
                    startScanStatic();
                }
            } else {
                log("Не все основные разрешения Bluetooth/Location были предоставлены. Функциональность Bluetooth может быть ограничена.");
                Toast.makeText(this, "Для работы с Bluetooth необходимы соответствующие разрешения.", Toast.LENGTH_LONG).show();
            }

            if (!writeStoragePermissionGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "Разрешение на запись в хранилище необходимо для сохранения истории.", Toast.LENGTH_LONG).show();
            }
            if (!postNotificationsPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, "Разрешение на показ уведомлений не предоставлено.", Toast.LENGTH_LONG).show();
            }
        }
    }


    @SuppressLint("MissingPermission")
    private void handleStaticGattClose() {
        log("Полное закрытие статического GATT соединения...");
        stopDataPolling();
        if (staticBluetoothGatt != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    staticBluetoothGatt.disconnect();
                    staticBluetoothGatt.close();
                } else {
                    Log.w(TAG, "Cannot close GATT due to missing BLUETOOTH_CONNECT permission.");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException on gatt close: " + e.getMessage());
            } finally {
                staticBluetoothGatt = null;
            }
        }
        staticConnectedDevice = null;
        staticIsScanning = false;
        synchronized (staticPacketBuffer) {
            staticPacketBuffer.clear();
        }
        staticLastReceivedPacket = null;

        current_data activity = currentActivityInstanceRef != null ? currentActivityInstanceRef.get() : null;
        if (activity != null) {
            activity.runOnUiThread(activity::resetConnectedDeviceUiOnly);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(GLUCOSE_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Зарегистрировать канал в системе
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Канал уведомлений '" + GLUCOSE_CHANNEL_ID + "' создан.");
            } else {
                Log.e(TAG, "NotificationManager is null, не удалось создать канал.");
            }
        }
    }

    private static void showGlucoseNotification(Context context, String message) {
        if (context == null) {
            Log.e(TAG, "showGlucoseNotification: context is null, cannot show notification.");
            return;
        }

        // Создаем Intent для открытия MainActivity при нажатии на уведомление
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, GLUCOSE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // ЗАМЕНИТЕ НА СВОЮ ИКОНКУ (например, R.drawable.ic_notification)
                .setContentTitle("MiaoTrack: Новое значение")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true) // Уведомление исчезнет по клику
                .setContentIntent(pendingIntent) // Добавляем PendingIntent
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC); // Показать на экране блокировки

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

        // Проверка разрешения POST_NOTIFICATIONS для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Разрешение POST_NOTIFICATIONS не предоставлено. Уведомление не будет показано.");
                // Здесь можно запросить разрешение, если это подходящий момент,
                // но обычно запрос делают в Activity.
                return;
            }
        }
        try {
            notificationManager.notify(GLUCOSE_NOTIFICATION_ID, builder.build());
            Log.d(TAG, "Уведомление показано: " + message);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException при показе уведомления. Убедитесь, что разрешение POST_NOTIFICATIONS есть в манифесте и предоставлено.", e);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("onDestroy вызван для экземпляра Activity: " + this.toString());
        if (currentActivityInstanceRef != null && currentActivityInstanceRef.get() == this) {
            // currentActivityInstanceRef.clear();
        }
    }

    public static class App {
        private static Context appContext;
        public static void init(Context context) {
            if (appContext == null && context != null) {
                appContext = context.getApplicationContext();
            }
        }
        public static Context getAppContext() {
            if (currentActivityInstanceRef != null && currentActivityInstanceRef.get() != null) {
                return currentActivityInstanceRef.get().getApplicationContext();
            }
            return appContext;
        }
    }
}
