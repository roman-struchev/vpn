package com.vpn.android;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.vpn.android.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * A cold start from the P2P relay notification: the app opened with both tabs
 * on top of each other (Connect queued, then Profile switched to before
 * Connect existed, so it was never hidden).
 */
@RunWith(AndroidJUnit4.class)
public class OpenProfileFromNotificationTest {

    @Test
    public void onlyTheProfileTabIsShown() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PROFILE, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                List<String> visible = new ArrayList<>();
                for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                    if (f.isAdded() && !f.isHidden() && f.getTag() != null) visible.add(f.getTag());
                }
                assertEquals(List.of("profile"), visible);
            });
        }
    }
}
