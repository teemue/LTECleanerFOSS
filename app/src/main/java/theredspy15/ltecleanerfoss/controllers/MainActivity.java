/*
 * Copyright 2020 Hunter J Drum
 */

package theredspy15.ltecleanerfoss.controllers;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

import theredspy15.ltecleanerfoss.FileScanner;
import theredspy15.ltecleanerfoss.R;
import theredspy15.ltecleanerfoss.databinding.ActivityMainBinding;
import theredspy15.ltecleanerfoss.workers.CleanWorker;

public class MainActivity extends AppCompatActivity {

    static boolean running = false;
    public static SharedPreferences prefs;

    public ActivityMainBinding binding;

    @SuppressLint("LogConditional")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.cleanButton.setOnClickListener(this::clean);
        binding.settingsButton.setOnClickListener(this::settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        WhitelistActivity.getWhiteList();

        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(CleanWorker.class, 16, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance().enqueueUniquePeriodicWork("cleanworker",ExistingPeriodicWorkPolicy.KEEP,periodicWork);
    }

    /**
     * Starts the settings activity
     * @param view the view that is clickedprefs = getSharedPreferences("Settings",0);
     */
    public final void settings(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    /**
     * Runs search and delete on background thread
     */
    public final void clean(View view) {
        requestWriteExternalPermission();

        if (!running) {
            if (!prefs.getBoolean("one_click", false)) // one-click disabled
                new AlertDialog.Builder(this,R.style.MyAlertDialogTheme)
                        .setTitle(R.string.select_task)
                        .setMessage(R.string.do_you_want_to)
                        .setPositiveButton(R.string.clean, (dialog, whichButton) -> { // clean
                            new Thread(()-> scan(true)).start();
                        })
                        .setNegativeButton(R.string.analyze, (dialog, whichButton) -> { // analyze
                            new Thread(()-> scan(false)).start();
                        }).show();
            else new Thread(()-> scan(true)).start(); // one-click enabled
        }
    }

    public void animateBtn() {
        binding.topSpacer.setVisibility(View.GONE);
        binding.fileScrollView.setVisibility(View.VISIBLE);
    }

    /**
     * Searches entire device, adds all files to a list, then a for each loop filters
     * out files for deletion. Repeats the process as long as it keeps finding files to clean,
     * unless nothing is found to begin with
     */
    @SuppressLint("SetTextI18n")
    private void scan(boolean delete) {
        Looper.prepare();
        running = true;
        runOnUiThread(()->findViewById(R.id.cleanButton).setEnabled(!running));
        reset();

        File path = Environment.getExternalStorageDirectory();

        // scanner setup
        FileScanner fs = new FileScanner(path, this)
                .setEmptyDir(prefs.getBoolean("empty", false))
                .setAutoWhite(prefs.getBoolean("auto_white", true))
                .setDelete(delete)
                .setCorpse(prefs.getBoolean("corpse", false))
                .setGUI(binding)
                .setContext(this)
                .setUpFilters(
                        prefs.getBoolean("generic", true),
                        prefs.getBoolean("aggressive", false),
                        prefs.getBoolean("apk", false));

        // failed scan
        if (path.listFiles() == null) { // is this needed? yes.
            TextView textView = printTextView("Scan failed.", Color.RED);
            runOnUiThread(() -> binding.fileListView.addView(textView));
        }

        runOnUiThread(() -> {
            animateBtn();
            binding.statusTextView.setText(getString(R.string.status_running));

            // crappy but working fix for percentage never reaching 100 exactly
            binding.scanProgress.setProgress(binding.scanProgress.getMax());
        });

        // kilobytes found/freed text
        long kilobytesTotal = fs.startScan();
        runOnUiThread(() -> {
            if (delete) {
                binding.statusTextView.setText(getString(R.string.freed) + " " + convertSize(kilobytesTotal));
            } else {
                binding.statusTextView.setText(getString(R.string.found) + " " + convertSize(kilobytesTotal));
            }
            TextView textView = binding.frameLayout.findViewById(R.id.scanTextView);
            textView.setText("100%");// crappy but working fix for percentage never reaching 100 exactly
        });
        binding.fileScrollView.post(() -> binding.fileScrollView.fullScroll(ScrollView.FOCUS_DOWN));

        running = false;
        runOnUiThread(()->findViewById(R.id.cleanButton).setEnabled(!running));
        Looper.loop();
    }

    /**
     * Convenience method to quickly create a textview
     * @param text - text of textview
     * @return - created textview
     */
    private synchronized TextView printTextView(String text, int color) {
        TextView textView = new TextView(MainActivity.this);
        textView.setTextColor(color);
        textView.setText(text);
        textView.setPadding(3,3,3,3);
        return textView;
    }

    public static String convertSize(long length) {
        final DecimalFormat format = new DecimalFormat("#.##");
        final long MiB = 1024 * 1024;
        final long KiB = 1024;

        if (length > MiB) {
            return format.format(length / MiB) + " MB";
        }
        if (length > KiB) {
            return format.format(length / KiB) + " KB";
        }
        return format.format(length) + " B";
    }

    /**
     * Increments amount removed, then creates a text view to add to the scroll view.
     * If there is any error while deleting, turns text view of path red
     * @param file file to delete
     */
    public synchronized TextView displayPath(File file) {
        // creating and adding a text view to the scroll view with path to file
        TextView textView = printTextView(file.getAbsolutePath(), getResources().getColor(R.color.colorAccent));

        // adding to scroll view
        runOnUiThread(() -> binding.fileListView.addView(textView));

        // scroll to bottom
        binding.fileScrollView.post(() -> binding.fileScrollView.fullScroll(ScrollView.FOCUS_DOWN));

        return textView;
    }


    /**
     * Removes all views present in fileListView (linear view), and sets found and removed
     * files to 0
     */
    private synchronized void reset() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        runOnUiThread(() -> {
            binding.fileListView.removeAllViews();
            binding.scanProgress.setProgress(0);
            binding.scanProgress.setMax(1);
        });
    }

    /**
     * Request write permission
     */
    public synchronized void requestWriteExternalPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // android 11 and up
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.MANAGE_EXTERNAL_STORAGE},
                    1);

            if (!Environment.isExternalStorageManager()) { // all files
                Toast.makeText(this, "Permission needed!", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    1);
        }
    }

    /**
     * Handles the whether the user grants permission. Launches new fragment asking the user to give file permission.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 &&
                grantResults.length > 0 &&
                grantResults[0] != PackageManager.PERMISSION_GRANTED)
            prompt();
    }

    /**
     * Launches the prompt activity
     */
    public final void prompt() {
        Intent intent = new Intent(this, PromptActivity.class);
        startActivity(intent);
    }
}
