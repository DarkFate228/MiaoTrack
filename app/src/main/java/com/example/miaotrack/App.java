package com.example.miaotrack; // Ваш пакет
import android.app.Application;
import android.content.Context;

public class App extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context getAppContext() {
        return appContext;
    }
    public static void init(Context context) { // Дополнительный метод инициализации
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext();
        }
    }
}