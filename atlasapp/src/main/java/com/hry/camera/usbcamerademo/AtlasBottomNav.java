package com.hry.camera.usbcamerademo;

import android.app.Activity;
import android.content.Intent;
import android.support.design.widget.BottomNavigationView;

/**
 * Requirement 6: bottom nav only has Record / Review / Me. Each tab is its own Activity;
 * switching tabs reuses an existing instance (REORDER_TO_FRONT) instead of piling up a back stack.
 */
public final class AtlasBottomNav {
    public static final int TAB_RECORD = 0;
    public static final int TAB_REVIEW = 1;
    public static final int TAB_ME = 2;

    private AtlasBottomNav() {
    }

    public static void setup(final Activity activity, int currentTab) {
        BottomNavigationView navView = (BottomNavigationView) activity.findViewById(R.id.bottomNav);
        if (navView == null) {
            return;
        }
        switch (currentTab) {
            case TAB_RECORD:
                navView.setSelectedItemId(R.id.nav_record);
                break;
            case TAB_REVIEW:
                navView.setSelectedItemId(R.id.nav_review);
                break;
            case TAB_ME:
                navView.setSelectedItemId(R.id.nav_me);
                break;
            default:
                break;
        }
        navView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(android.view.MenuItem item) {
                Class<?> target = null;
                int id = item.getItemId();
                if (id == R.id.nav_record) {
                    target = MainActivity.class;
                } else if (id == R.id.nav_review) {
                    target = ReviewShellActivity.class;
                } else if (id == R.id.nav_me) {
                    target = MeActivity.class;
                }
                if (target == null || target.equals(activity.getClass())) {
                    return true;
                }
                Intent intent = new Intent(activity, target);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return true;
            }
        });
    }
}
