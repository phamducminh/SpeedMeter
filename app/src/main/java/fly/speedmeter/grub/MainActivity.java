package fly.speedmeter.grub;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Chronometer;
import android.widget.TextView;

import com.gc.materialdesign.views.ProgressBarCircularIndeterminate;
import com.gc.materialdesign.widgets.Dialog;
import com.google.gson.Gson;
import com.melnykov.fab.FloatingActionButton;

import java.util.Locale;

import fly.speedmeter.grub.data.OverpassDataSource;
import fly.speedmeter.grub.utils.SystemServicesHelper;


public class MainActivity extends ActionBarActivity
        implements LocationListener, GpsStatus.Listener, SensorEventListener {

    private static final String TAG = "MainActivity";

    public static final int REQUEST_ACCESS_FINE_LOCATION = 2;

    private SharedPreferences sharedPreferences;
    private LocationManager mLocationManager;
    private static Data data;

    private Toolbar toolbar;
    private FloatingActionButton fab;
    private FloatingActionButton refresh;
    private ProgressBarCircularIndeterminate progressBarCircularIndeterminate;
    private TextView satellite;
    private TextView status;
    private TextView accuracy;
    private TextView currentSpeed;
    private TextView maxSpeed;
    private TextView averageSpeed;
    private TextView distance;
    private Chronometer time;
    private Data.onGpsServiceUpdate onGpsServiceUpdate;

    private boolean firstfix;

    private static CalcThread calcThread;
    private static TextView currentGForce, minGForce, maxGForce;
    private static SensorManager sensorMgr = null;

    private static boolean calibrate = false;
    private float lastUpdate;

    private static TextView speedLimit;
    private SystemServicesHelper mSystemServicesHelper;
    private OverpassDataSource mOverpassDataSource;

    private class MyHandler extends Handler {
        public void handleMessage(Message msg) {
            String[] values = (String[]) msg.obj;
            currentGForce.setText(values[0]);
            minGForce.setText(values[1]);
            maxGForce.setText(values[2]);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        data = new Data(onGpsServiceUpdate);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        //setTitle("");
        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setVisibility(View.INVISIBLE);

        refresh = (FloatingActionButton) findViewById(R.id.refresh);
        refresh.setVisibility(View.INVISIBLE);

        onGpsServiceUpdate = new Data.onGpsServiceUpdate() {
            @Override
            public void update() {
                double maxSpeedTemp = data.getMaxSpeed();
                double distanceTemp = data.getDistance();
                double averageTemp;
                if (sharedPreferences.getBoolean("auto_average", false)) {
                    averageTemp = data.getAverageSpeedMotion();
                } else {
                    averageTemp = data.getAverageSpeed();
                }

                String speedUnits;
                String distanceUnits;
                if (sharedPreferences.getBoolean("miles_per_hour", false)) {
                    maxSpeedTemp *= 0.62137119;
                    distanceTemp = distanceTemp / 1000.0 * 0.62137119;
                    averageTemp *= 0.62137119;
                    speedUnits = "mi/h";
                    distanceUnits = "mi";
                } else {
                    speedUnits = "km/h";
                    if (distanceTemp <= 1000.0) {
                        distanceUnits = "m";
                    } else {
                        distanceTemp /= 1000.0;
                        distanceUnits = "km";
                    }
                }

                SpannableString s = new SpannableString(String.format("%.0f", maxSpeedTemp) + speedUnits);
                s.setSpan(new RelativeSizeSpan(0.5f), s.length() - 4, s.length(), 0);
                maxSpeed.setText(s);

                s = new SpannableString(String.format("%.0f", averageTemp) + speedUnits);
                s.setSpan(new RelativeSizeSpan(0.5f), s.length() - 4, s.length(), 0);
                averageSpeed.setText(s);

                s = new SpannableString(String.format("%.3f", distanceTemp) + distanceUnits);
                s.setSpan(new RelativeSizeSpan(0.5f), s.length() - 2, s.length(), 0);
                distance.setText(s);
            }
        };

        mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        satellite = (TextView) findViewById(R.id.satellite);
        status = (TextView) findViewById(R.id.status);
        accuracy = (TextView) findViewById(R.id.accuracy);
        maxSpeed = (TextView) findViewById(R.id.maxSpeed);
        averageSpeed = (TextView) findViewById(R.id.averageSpeed);
        distance = (TextView) findViewById(R.id.distance);
        time = (Chronometer) findViewById(R.id.time);
        currentSpeed = (TextView) findViewById(R.id.currentSpeed);
        progressBarCircularIndeterminate = (ProgressBarCircularIndeterminate) findViewById(R.id.progressBarCircularIndeterminate);

        time.setText("00:00:00");
        time.setOnChronometerTickListener(new Chronometer.OnChronometerTickListener() {
            boolean isPair = true;

            @Override
            public void onChronometerTick(Chronometer chrono) {
                long time;
                if (data.isRunning()) {
                    time = SystemClock.elapsedRealtime() - chrono.getBase();
                    data.setTime(time);
                } else {
                    time = data.getTime();
                }

                int h = (int) (time / 3600000);
                int m = (int) (time - h * 3600000) / 60000;
                int s = (int) (time - h * 3600000 - m * 60000) / 1000;
                String hh = h < 10 ? "0" + h : h + "";
                String mm = m < 10 ? "0" + m : m + "";
                String ss = s < 10 ? "0" + s : s + "";
                chrono.setText(hh + ":" + mm + ":" + ss);

                if (data.isRunning()) {
                    chrono.setText(hh + ":" + mm + ":" + ss);
                } else {
                    if (isPair) {
                        isPair = false;
                        chrono.setText(hh + ":" + mm + ":" + ss);
                    } else {
                        isPair = true;
                        chrono.setText("");
                    }
                }

            }
        });

        currentGForce = (TextView) findViewById(R.id.currentGForce);
        minGForce = (TextView) findViewById(R.id.minGForce);
        maxGForce = (TextView) findViewById(R.id.maxGForce);

        lastUpdate = 0;
        calibrate = true;

        speedLimit = (TextView) findViewById(R.id.speedLimit);
        mSystemServicesHelper = new SystemServicesHelper(this, this);
        mOverpassDataSource = new OverpassDataSource();
        mOverpassDataSource.setOnMaxSpeedDetectedListener(
                new OverpassDataSource.OnMaxSpeedDetectedListener() {
                    @Override
                    public void maxSpeedDetected(@Nullable String speed,
                                                 @Nullable String nodeId,
                                                 @Nullable String wayId,
                                                 @Nullable String wayName,
                                                 float distance) {
//                        mAutoSpeedLimitFragment.setSpeedValueWithInfo(
//                                speed,
//                                wayId,
//                                wayName,
//                                DateFormat.getTimeInstance()
//                                        .format(Calendar.getInstance().getTime()));
                        speedLimit.setText(speed);
                    }
                }
        );
    }

    public void requestAccessFineLocationPermission() {
        Log.i(TAG, "ACCESS_FINE_LOCATION permission has NOT been granted. Requesting permission.");

        // BEGIN_INCLUDE(access_fine_location_permission_request)
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Provide an additional rationale to the user if the permission was not granted
            // and the user would benefit from additional context for the use of the permission.
            // For example if the user has previously denied the permission.
            Log.i(TAG,
                    "Displaying access fine location permission rationale to provide additional context.");
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_ACCESS_FINE_LOCATION);
        } else {

            // Access Fine Location permission has not been granted yet. Request it directly.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_ACCESS_FINE_LOCATION);
        }
        // END_INCLUDE(access_fine_location_permission_request)
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_ACCESS_FINE_LOCATION) {
            // BEGIN_INCLUDE(permission_result)
            // Received permission result for internet permission.
            Log.i(TAG, "Received response for Access Fine Location permission request.");

            // Check if the only required permission has been granted
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Access Fine Location permission has been granted, preview can be displayed
                Log.i(TAG, "ACCESS_FINE_LOCATION permission has now been granted.");
                showSnackBar(getString(R.string.permission_available_access_fine_location));
                doResume();
            } else {
                Log.i(TAG, "ACCESS_FINE_LOCATION permission was NOT granted.");
                showSnackBar(getString(R.string.permissions_not_granted));
            }
            // END_INCLUDE(permission_result)
//        } else if (requestCode == REQUEST_ACCESS_COARSE_LOCATION) {
//            // BEGIN_INCLUDE(permission_result)
//            // Received permission result for access network state permission.
//            Log.i(TAG, "Received response for Access Network State permission request.");
//
//            // Check if the only required permission has been granted
//            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                // Access Network State permission has been granted, preview can be displayed
//                Log.i(TAG, "ACCESS_NETWORK_STATE permission has now been granted.");
//                showSnackBar(getString(R.string.permission_available_access_coarse_location));
//                mPresenter.beginUpdates();
//            } else {
//                Log.i(TAG, "ACCESS_NETWORK_STATE permission was NOT granted.");
//                showSnackBar(getString(R.string.permissions_not_granted));
//            }
//            // END_INCLUDE(permission_result)
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    protected void showSnackBar(String message) {
        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content),
                message, Snackbar.LENGTH_SHORT);
        View sbView = snackbar.getView();
        TextView textView = (TextView) sbView
                .findViewById(android.support.design.R.id.snackbar_text);
        textView.setTextColor(ContextCompat.getColor(this, R.color.white));
        snackbar.show();
    }

    public void onFabClick(View v) {
        if (!data.isRunning()) {
            fab.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_pause));
            data.setRunning(true);
            time.setBase(SystemClock.elapsedRealtime() - data.getTime());
            time.start();
            data.setFirstTime(true);
            startService(new Intent(getBaseContext(), GpsServices.class));
            refresh.setVisibility(View.INVISIBLE);
            calibrate = true;
        } else {
            fab.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_play));
            data.setRunning(false);
            status.setText("");
            stopService(new Intent(getBaseContext(), GpsServices.class));
            refresh.setVisibility(View.VISIBLE);
        }
    }

    public void onRefreshClick(View v) {
        resetData();
        stopService(new Intent(getBaseContext(), GpsServices.class));
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGpsDisabledDialog();
        }

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Internet permission has not been granted.
            requestAccessFineLocationPermission();
        } else {
            doResume();
        }
    }

    void doResume() {
        startSensing();
        mSystemServicesHelper.trySetLocationUpdateListener();
        firstfix = true;
        if (!data.isRunning()) {
            Gson gson = new Gson();
            String json = sharedPreferences.getString("data", "");
            data = gson.fromJson(json, Data.class);
        }
        if (data == null) {
            data = new Data(onGpsServiceUpdate);
        } else {
            data.setOnGpsServiceUpdate(onGpsServiceUpdate);
        }

        if (mLocationManager.getAllProviders().indexOf(LocationManager.GPS_PROVIDER) >= 0) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this);
        } else {
            Log.w("MainActivity", "No GPS location provider found. GPS data display will not be available.");
        }

        mLocationManager.addGpsStatusListener(this);

//        if (new SystemServicesHelper(MainActivity.this).checkNetwork()) {
//            mOverpassDataSource.setRadius(Constants.DEFAULT_RADIUS);
//            mOverpassDataSource.searchNearestMaxSpeed(
//                    Constants.DEFAULT_LATITUDE,
//                    Constants.DEFAULT_LONGITUDE);
//        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSensing();
        mSystemServicesHelper.removeLocationUpdateListener();
        mLocationManager.removeUpdates(this);
        mLocationManager.removeGpsStatusListener(this);
        SharedPreferences.Editor prefsEditor = sharedPreferences.edit();
        Gson gson = new Gson();
        String json = gson.toJson(data);
        prefsEditor.putString("data", json);
        prefsEditor.commit();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService(new Intent(getBaseContext(), GpsServices.class));
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, Settings.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location.hasAccuracy()) {
            SpannableString s = new SpannableString(String.format("%.0f", location.getAccuracy()) + "m");
            s.setSpan(new RelativeSizeSpan(0.75f), s.length() - 1, s.length(), 0);
            accuracy.setText(s);

            if (firstfix) {
                status.setText("");
                fab.setVisibility(View.VISIBLE);
                if (!data.isRunning() && !maxSpeed.getText().equals("")) {
                    refresh.setVisibility(View.VISIBLE);
                }
                firstfix = false;
            }
        } else {
            firstfix = true;
        }

        if (location.hasSpeed()) {
            progressBarCircularIndeterminate.setVisibility(View.GONE);
            String speed = String.format(Locale.ENGLISH, "%.0f", location.getSpeed() * 3.6) + "km/h";

            if (sharedPreferences.getBoolean("miles_per_hour", false)) { // Convert to MPH
                speed = String.format(Locale.ENGLISH, "%.0f", location.getSpeed() * 3.6 * 0.62137119) + "mi/h";
            }
            SpannableString s = new SpannableString(speed);
            s.setSpan(new RelativeSizeSpan(0.25f), s.length() - 4, s.length(), 0);
            currentSpeed.setText(s);
        }

        mOverpassDataSource.setRadius(Constants.DEFAULT_RADIUS);
        mOverpassDataSource.searchNearestMaxSpeed(location.getLatitude(), location.getLongitude());

    }

    public void onGpsStatusChanged(int event) {
        switch (event) {
            case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                GpsStatus gpsStatus = mLocationManager.getGpsStatus(null);
                int satsInView = 0;
                int satsUsed = 0;
                Iterable<GpsSatellite> sats = gpsStatus.getSatellites();
                for (GpsSatellite sat : sats) {
                    satsInView++;
                    if (sat.usedInFix()) {
                        satsUsed++;
                    }
                }
                satellite.setText(String.valueOf(satsUsed) + "/" + String.valueOf(satsInView));
                if (satsUsed == 0) {
                    fab.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_play));
                    data.setRunning(false);
                    status.setText("");
                    stopService(new Intent(getBaseContext(), GpsServices.class));
                    fab.setVisibility(View.INVISIBLE);
                    refresh.setVisibility(View.INVISIBLE);
                    accuracy.setText("");
                    status.setText(getResources().getString(R.string.waiting_for_fix));
                    firstfix = true;
                }
                break;

            case GpsStatus.GPS_EVENT_STOPPED:
                if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    showGpsDisabledDialog();
                }
                break;
            case GpsStatus.GPS_EVENT_FIRST_FIX:
                break;
        }
    }

    public void showGpsDisabledDialog() {
        Dialog dialog = new Dialog(this, getResources().getString(R.string.gps_disabled), getResources().getString(R.string.please_enable_gps));

        dialog.setOnAcceptButtonClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
            }
        });
        dialog.show();
    }

    public void resetData() {
        fab.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_play));
        refresh.setVisibility(View.INVISIBLE);
        time.stop();
        maxSpeed.setText("");
        averageSpeed.setText("");
        distance.setText("");
        time.setText("00:00:00");
        data = new Data(onGpsServiceUpdate);
        calibrate = true;
    }

    public static Data getData() {
        return data;
    }

    public void onBackPressed() {
        Intent a = new Intent(Intent.ACTION_MAIN);
        a.addCategory(Intent.CATEGORY_HOME);
        a.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(a);
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER: {
                float currTime = System.currentTimeMillis();
                if (currTime < (lastUpdate + 500))
                    return;

                lastUpdate = currTime;

                if (calcThread == null) {
                    Log.d("MainActivity", "CalcThread not running");
                    return;
                }

                Handler h = calcThread.getHandler();
                if (h == null) {
                    Log.e("MainActivity", "Failed to get CalcThread Handler");
                    return;
                }

                Message m = Message.obtain(h);
                if (m == null) {
                    Log.e("MainActivity", "Failed to get Message instance");
                    return;
                }

                m.obj = (Object) sensorEvent.values[1];
                if (calibrate) {
                    calibrate = false;

                    m.what = CalcThread.CALIBRATE;
                    h.sendMessageAtFrontOfQueue(m);
                } else {
                    m.what = CalcThread.GRAVITY_CHANGE;
                    m.obj = (Object) sensorEvent.values[1];
                    m.sendToTarget();
                }

                break;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void startSensing() {
        if (calcThread == null) {
            calcThread = new CalcThread("MainActivity", new MyHandler());
            calcThread.start();
        }

        if (sensorMgr == null) {
            sensorMgr = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor sensor = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

            if (!sensorMgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) {
                // on accelerometer on this device
                Log.e("MainActivity", "No accelerometer available");
                sensorMgr.unregisterListener(this, sensor);
                sensorMgr = null;
                return;
            }
        }

        calibrate = true;
    }

    private void stopSensing() {
        if (sensorMgr != null) {
            sensorMgr.unregisterListener(this);
            sensorMgr = null;
        }

        if (calcThread != null) {
            Handler h = calcThread.getHandler();
            if (h != null) {
                Message m = Message.obtain(h);
                if (m != null) {
                    m.what = CalcThread.SENSOR_STOP;
                    h.sendMessageAtFrontOfQueue(m);
                }
            }

            calcThread = null;
        }
    }
}
