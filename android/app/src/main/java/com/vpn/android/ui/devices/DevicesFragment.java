package com.vpn.android.ui.devices;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.databinding.FragmentDevicesBinding;
import com.vpn.android.util.Async;

public class DevicesFragment extends Fragment {

    private FragmentDevicesBinding binding;
    private ApiClient apiClient;
    private DeviceAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentDevicesBinding.inflate(inflater, container, false);
        apiClient = new ApiClient(new TokenStore(requireContext()));
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new DeviceAdapter(this::confirmRevoke);
        binding.devicesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.devicesList.setAdapter(adapter);

        binding.swipeRefresh.setOnRefreshListener(this::loadDevices);
        binding.addDeviceButton.setOnClickListener(v -> showAddDeviceDialog());

        loadDevices();
    }

    private void loadDevices() {
        binding.swipeRefresh.setRefreshing(true);
        Async.run(
                () -> apiClient.getDevices(),
                devices -> {
                    binding.swipeRefresh.setRefreshing(false);
                    adapter.submitList(devices);
                },
                error -> {
                    binding.swipeRefresh.setRefreshing(false);
                    Snackbar.make(binding.getRoot(), error.getMessage(), Snackbar.LENGTH_LONG).show();
                });
    }

    private void showAddDeviceDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.device_name_hint);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_device_action)
                .setView(input)
                .setPositiveButton(R.string.add_device_action, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        addDevice(name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addDevice(String name) {
        Async.run(
                () -> apiClient.addDevice(name, "ANDROID"),
                device -> loadDevices(),
                error -> Snackbar.make(binding.getRoot(), error.getMessage(), Snackbar.LENGTH_LONG).show());
    }

    private void confirmRevoke(DeviceDto device) {
        new AlertDialog.Builder(requireContext())
                .setMessage(device.deviceName)
                .setPositiveButton(R.string.revoke_device_action, (dialog, which) -> revokeDevice(device))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void revokeDevice(DeviceDto device) {
        Async.run(
                () -> {
                    apiClient.deleteDevice(device.id);
                    return null;
                },
                ignored -> loadDevices(),
                error -> Snackbar.make(binding.getRoot(), error.getMessage(), Snackbar.LENGTH_LONG).show());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
