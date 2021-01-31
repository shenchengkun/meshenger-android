package d.d.meshenger;

import android.app.Dialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v7.app.AlertDialog;
import android.os.Bundle;
import android.support.v7.app.AppCompatDelegate;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;


public class SettingsActivity extends MeshengerActivity implements ServiceConnection {
    private MainService.MainBinder binder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        setTitle(getResources().getString(R.string.menu_settings));

        bindService();
        initViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (this.binder != null) {
            unbindService(this);
        }
    }

    private void bindService() {
        // ask MainService to get us the binder object
        Intent serviceIntent = new Intent(this, MainService.class);
        bindService(serviceIntent, this, Service.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        this.binder = (MainService.MainBinder) iBinder;
        initViews();
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        this.binder = null;
    }

    private boolean getIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pMgr = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
            return pMgr.isIgnoringBatteryOptimizations(this.getPackageName());
        }
        return false;
    }

    private void initViews() {
        if (this.binder == null) {
            return;
        }

        findViewById(R.id.changeNameLayout).setOnClickListener((View view) -> {
            showChangeNameDialog();
        });

        findViewById(R.id.changeAddressLayout).setOnClickListener((View view) -> {
            Intent intent = new Intent(this, AddressActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.changePasswordLayout).setOnClickListener((View view) -> {
            showChangePasswordDialog();
        });

        findViewById(R.id.changeIceServersLayout).setOnClickListener((View view) -> {
            showChangeIceServersDialog();
        });

        String username = this.binder.getSettings().getUsername();
        ((TextView) findViewById(R.id.nameTv)).setText(
            username.length() == 0 ? getResources().getString(R.string.none) : username
        );

        List<String> addresses = this.binder.getSettings().getAddresses();
        ((TextView) findViewById(R.id.addressTv)).setText(
            addresses.size() == 0 ? getResources().getString(R.string.none) : Utils.join(addresses)
        );

        String password = this.binder.getDatabasePassword();
        ((TextView) findViewById(R.id.passwordTv)).setText(
            password.isEmpty() ? getResources().getString(R.string.none) : "********"
        );

        List<String> iceServers = this.binder.getSettings().getIceServers();
        ((TextView) findViewById(R.id.iceServersTv)).setText(
            iceServers.isEmpty() ? getResources().getString(R.string.none) : Utils.join(iceServers)
        );

        boolean blockUnknown = this.binder.getSettings().getBlockUnknown();
        CheckBox blockUnknownCB = findViewById(R.id.checkBoxBlockUnknown);
        blockUnknownCB.setChecked(blockUnknown);
        blockUnknownCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setBlockUnknown(isChecked);
            this.binder.saveDatabase();
        });

        boolean nightMode = this.binder.getSettings().getNightMode();
        CheckBox nightModeCB = findViewById(R.id.checkBoxNightMode);
        nightModeCB.setChecked(nightMode);
        nightModeCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // apply value
            AppCompatDelegate.setDefaultNightMode(
                isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
            );

            // save value
            this.binder.getSettings().setNightMode(isChecked);
            this.binder.saveDatabase();

            // apply theme
            this.recreate();
        });

        boolean sendAudio = this.binder.getSettings().getSendAudio();
        CheckBox sendAudioCB = findViewById(R.id.checkBoxSendAudio);
        sendAudioCB.setChecked(sendAudio);
        sendAudioCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setSendAudio(isChecked);
            this.binder.saveDatabase();
        });

        boolean receiveAudio = this.binder.getSettings().getReceiveAudio();
        CheckBox receiveAudioCB = findViewById(R.id.checkBoxReceiveAudio);
        receiveAudioCB.setChecked(receiveAudio);
        receiveAudioCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setReceiveAudio(isChecked);
            this.binder.saveDatabase();
        });

        boolean sendVideo = this.binder.getSettings().getSendVideo();
        CheckBox sendVideoCB = findViewById(R.id.checkBoxSendVideo);
        sendVideoCB.setChecked(sendVideo);
        sendVideoCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setSendVideo(isChecked);
            this.binder.saveDatabase();
        });

        boolean receiveVideo = this.binder.getSettings().getReceiveVideo();
        CheckBox receiveVideoCB = findViewById(R.id.checkBoxReceiveVideo);
        receiveVideoCB.setChecked(receiveVideo);
        receiveVideoCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setReceiveVideo(isChecked);
            this.binder.saveDatabase();
        });

        boolean autoAcceptCall = this.binder.getSettings().getAutoAcceptCall();
        CheckBox autoAcceptCallCB = findViewById(R.id.checkBoxAutoAcceptCall);
        autoAcceptCallCB.setChecked(autoAcceptCall);
        autoAcceptCallCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setAutoAcceptCall(isChecked);
            this.binder.saveDatabase();
        });

        boolean ignoreBatteryOptimizations = getIgnoreBatteryOptimizations();
        CheckBox ignoreBatteryOptimizationsCB = findViewById(R.id.checkBoxIgnoreBatteryOptimizations);
        ignoreBatteryOptimizationsCB.setChecked(ignoreBatteryOptimizations);
        ignoreBatteryOptimizationsCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // Only required for Adroind 6 or later
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + this.getPackageName()));
                this.startActivity(intent);
            }
        });

        boolean developmentMode = this.binder.getSettings().getDevelopmentMode();
        CheckBox developmentModeCB = findViewById(R.id.checkBoxDevelopmentMode);
        developmentModeCB.setChecked(developmentMode);
        developmentModeCB.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save value
            this.binder.getSettings().setDevelopmentMode(isChecked);
            this.binder.saveDatabase();
        });

        if (developmentMode) {
            findViewById(R.id.changeIgnoreBatteryOptimizations).setVisibility(View.VISIBLE);
            findViewById(R.id.changeDevelopmentModeLayout).setVisibility(View.VISIBLE);
            findViewById(R.id.changeIceServersLayout).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.changeIgnoreBatteryOptimizations).setVisibility(View.GONE);
            findViewById(R.id.changeDevelopmentModeLayout).setVisibility(View.GONE);
            findViewById(R.id.changeIceServersLayout).setVisibility(View.GONE);
        }
    }

    private void showChangeNameDialog() {
        String username = this.binder.getSettings().getUsername();
        EditText et = new EditText(this);
        et.setText(username);
        et.setSelection(username.length());
        new AlertDialog.Builder(this)
            .setTitle(getResources().getString(R.string.settings_change_name))
            .setView(et)
            .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                String new_username = et.getText().toString().trim();
                if (Utils.isValidName(new_username)) {
                    this.binder.getSettings().setUsername(new_username);
                    this.binder.saveDatabase();
                    initViews();
                } else {
                    Toast.makeText(this, getResources().getString(R.string.invalid_name), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(getResources().getText(R.string.cancel), null)
            .show();
    }

    private void showChangePasswordDialog() {
        String password = this.binder.getDatabasePassword();
        EditText et = new EditText(this);
        et.setText(password);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSelection(password.length());
        new AlertDialog.Builder(this)
            .setTitle(getResources().getString(R.string.settings_change_password))
            .setView(et)
            .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                String new_password = et.getText().toString();
                this.binder.setDatabasePassword(new_password);
                this.binder.saveDatabase();
                initViews();
            })
            .setNegativeButton(getResources().getText(R.string.cancel), null)
            .show();
    }

    private void showChangeIceServersDialog() {
        Settings settings = SettingsActivity.this.binder.getSettings();

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_set_ice_server);

        TextView iceServersTextView = dialog.findViewById(R.id.iceServersEditText);
        Button saveButton = dialog.findViewById(R.id.SaveButton);
        Button abortButton = dialog.findViewById(R.id.AbortButton);

        iceServersTextView.setText(Utils.join(settings.getIceServers()));

        saveButton.setOnClickListener((View v) -> {
            List<String> iceServers = Utils.split(iceServersTextView.getText().toString());
            settings.setIceServers(iceServers);

            // done
            Toast.makeText(SettingsActivity.this, R.string.done, Toast.LENGTH_SHORT).show();

            dialog.cancel();
        });

        abortButton.setOnClickListener((View v) -> {
            dialog.cancel();
        });

        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initViews();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        Intent intent = new Intent(SettingsActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        startActivity(intent);
    }

    private void log(String s) {
        Log.d(SettingsActivity.class.getSimpleName(), s);
    }
}
