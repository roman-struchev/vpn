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
import java.util.List;
import com.vpn.android.billing.PlanSummary;

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
                () -> {
                    List<DeviceDto> devices = apiClient.getDevices();
                    // The allowance comes from the plan, so it needs the
                    // profile's tariffId plus the catalogue. Best-effort: a
                    // failure here costs the counter, never the device list.
                    Integer maxDevices = null;
                    try {
                        maxDevices = PlanSummary.of(apiClient.getProfile(), apiClient.getTariffs()).maxDevices();
                    } catch (Exception ignored) {
                        // counter stays unknown
                    }
                    return new DeviceListWithAllowance(devices, maxDevices);
                },
                loaded -> {
                    binding.swipeRefresh.setRefreshing(false);
                    adapter.submitList(loaded.devices);
                    renderUsage(loaded.devices.size(), loaded.maxDevices);
                },
                error -> {
                    binding.swipeRefresh.setRefreshing(false);
                    Snackbar.make(binding.getRoot(), error.getMessage(), Snackbar.LENGTH_LONG).show();
                });
    }

    /** Carries the list together with what the plan allows — see loadDevices. */
    private static final class DeviceListWithAllowance {
        final List<DeviceDto> devices;
        final Integer maxDevices;

        DeviceListWithAllowance(List<DeviceDto> devices, Integer maxDevices) {
            this.devices = devices;
            this.maxDevices = maxDevices;
        }
    }

    private void renderUsage(int used, Integer maxDevices) {
        binding.devicesUsageText.setText(maxDevices != null
                ? getString(R.string.devices_usage, used, maxDevices)
                : getString(R.string.devices_usage_unknown, used));
        binding.devicesEmptyText.setVisibility(used == 0 ? View.VISIBLE : View.GONE);
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
                .setTitle(R.string.revoke_device_confirm_title)
                .setMessage(getString(R.string.confirm_revoke_device, device.deviceName))
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
