package com.vpn.android.ui.devices;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.databinding.ItemDeviceBinding;

import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    public interface OnRevokeListener {
        void onRevoke(DeviceDto device);
    }

    private final List<DeviceDto> devices = new ArrayList<>();
    private final OnRevokeListener onRevokeListener;

    public DeviceAdapter(OnRevokeListener onRevokeListener) {
        this.onRevokeListener = onRevokeListener;
    }

    public void submitList(List<DeviceDto> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDeviceBinding binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceDto device = devices.get(position);
        holder.binding.deviceName.setText(device.deviceName);
        holder.binding.devicePlatform.setText(device.platform);
        holder.binding.revokeButton.setOnClickListener(v -> onRevokeListener.onRevoke(device));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemDeviceBinding binding;

        ViewHolder(ItemDeviceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
