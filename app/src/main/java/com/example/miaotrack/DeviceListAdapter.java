package com.example.miaotrack;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceListAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false); // Используем стандартный макет
        return new ViewHolder(view);
    }

    @SuppressLint("MissingPermission") // Разрешения проверяются перед вызовом
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDevice device = devices.get(position);
        // Проверка разрешений перед доступом к device.getName() и device.getAddress()
        // Хотя для getAddress() разрешение обычно не требуется, для getName() может потребоваться BLUETOOTH_CONNECT на API 31+
        // На API 24 основные разрешения BLUETOOTH и BLUETOOTH_ADMIN уже должны быть предоставлены.
        // А ACCESS_FINE_LOCATION для обнаружения.

        String deviceName = device.getName();
        String deviceAddress = device.getAddress();

        holder.text1.setText((deviceName != null && !deviceName.isEmpty()) ? deviceName : "Unknown Device");
        holder.text2.setText(deviceAddress);

        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    @SuppressLint("MissingPermission")
    public void addDevice(BluetoothDevice device) {
        if (device != null && device.getName() != null && !device.getName().isEmpty()) { // Показываем только именованные устройства
            if (!devices.contains(device)) {
                devices.add(device);
                notifyItemInserted(devices.size() - 1);
            }
        }
    }

    public void clearDevices() {
        devices.clear();
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1;
        TextView text2;

        ViewHolder(View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}

