package sensors.andyimu_2;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // Constants
    private static final int TALKING_PORT = 1620;
    private static final String IP4V_PATTERN = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
            "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
            "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
            "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";

    // UI Variables
    private TextView logTv;
    private ToggleButton startBtn;
    private Button connectBtn;
    private EditText ipTF;
    private Switch magSw;
    private Switch angSw;
    private double RAD_TO_DEG;

    // Sensor interface variables
    private SensorManager sensorManager;
    private Sensor acc;
    private Sensor gyro;
    private Sensor magm;
    private AccelerometerListener accelerometerListener;
    private GyroscopeListener gyroscopeListener;
    private MagnetometerListener magnetometerListener;
    private static AtomicBoolean use_mag;                      // A thread safe way to share status between the threads

    // To store sensor output
    private static List acc_data, gyro_data, magm_data;

    // Network variables
    private boolean ipSet;
    private static String receiverIP;

    // Threading variables
    private FormattingLoop formattingLoop;
    private WifiTransmissionLoop pingSender;
    private static boolean loopIsOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // For thread safety
        acc_data = Collections.synchronizedList(new ArrayList<Float>());
        gyro_data = Collections.synchronizedList(new ArrayList<Float>());
        magm_data = Collections.synchronizedList(new ArrayList<Float>());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);       // keep the screen ON always

        // Access UI components
        logTv = findViewById(R.id.logTv);
        logTv.setMovementMethod(new ScrollingMovementMethod());                     // Enable scrolling in logTv
        startBtn = findViewById(R.id.startBtn);
        connectBtn = findViewById(R.id.connectBtn);
        ipTF = findViewById(R.id.ipTF);
        magSw = findViewById(R.id.magneto_sw);
        angSw = findViewById(R.id.angle_sw);
        startBtn.setEnabled(false);

        // Creating instances to access sensors
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        try{
            acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magm = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        catch (NullPointerException ex){
            logTv.append("Null pointer exception in finding the sensors.");
        }

        accelerometerListener = new AccelerometerListener();
        gyroscopeListener = new GyroscopeListener();
        magnetometerListener = new MagnetometerListener();

        formattingLoop = new FormattingLoop();      // Starts the looper which takes are of tasks related to formatting sensor value before sending
        pingSender = new WifiTransmissionLoop();    // Begin one instance of wifi transmission looper; making it ready to accept transmission tasks

        SystemClock.sleep(100);
        RAD_TO_DEG = 1;
        ipSet = false;
        loopIsOn = false;
        use_mag = new AtomicBoolean(false);
        receiverIP = "";
        for(int i=0;i<3;i++){
            acc_data.add(i);
            gyro_data.add(i);
            magm_data.add(i);
        }


        logTv.append("Hello! This is the status box.\n"
                    +" - To check the connection with server, press CONNECT and then PING\n"
                    +" - To toggle gyro data type between degrees per second and radians per second, use the switch"
                    +" - To append magneto data along with accelero and gryo, turn on the switch\n"
                    +" - To transmit data, press CONNECT followed by START\n"
                    +"-  To clear the status box, press CLEAR\n\n");
    }

    // Adding a menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        final MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.main_menu,menu);
        return true;
    }

    // Giving functionality to Menu items
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()){
            case R.id.ping:
                if(ipSet) {
                    pingSender.execute(new TransmittingRunnable(receiverIP, "PING"));   // Send PING to test connection
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Registering sensors when application starts else display an error
        if(acc !=null) sensorManager.registerListener(accelerometerListener,acc,SensorManager.SENSOR_DELAY_UI);
        else logTv.append("No accelerometer found\n");
        if(gyro !=null) sensorManager.registerListener(gyroscopeListener,gyro,SensorManager.SENSOR_DELAY_UI);
        else logTv.append("No gyroscope found\n");
        if(magm!=null){
            if(magSw.isChecked())
                sensorManager.registerListener(magnetometerListener, magm, SensorManager.SENSOR_DELAY_UI);
        }
        else logTv.append("No magnetometer found\n");
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Unregistering sensors when application stops
        sensorManager.unregisterListener(accelerometerListener);
        sensorManager.unregisterListener(gyroscopeListener);
        try{
            sensorManager.unregisterListener(magnetometerListener);
        }
        catch (Exception e){
            logTv.append("Some exception in removing magnetometer");
        }
        // stopping the loopers before ending program
        formattingLoop.quit();
        pingSender.quit();
    }


    // UI functions:

    public void startBtnOnClick(View v){
        if(loopIsOn){
            loopIsOn = false;
            logTv.append("\nStopped sending sensor values.\n");
        }
        else{

            if(use_mag.get()){
                logTv.append("\nSending accelerometer, gyroscope, and magnetometer values...\n");
            }
            else{
                logTv.append("\nSending accelerometer and gyroscope values...\n");
            }
            loopIsOn = true;
            // Send the runnable which formats the sensor values, into the looper
            formattingLoop.execute(new FormattingRunnable());
        }
    }

    public void connectBtnOnClick(View v){
        if(ipSet){
            ipTF.setEnabled(true);
            if(loopIsOn) startBtn.performClick();
            startBtn.setEnabled(false);
            ipSet = false;
            connectBtn.setText("CONNECT");
        }
        else{
            receiverIP = ipTF.getText().toString();
            ipSet = true;
            if(Pattern.matches(IP4V_PATTERN,receiverIP)){
                ipTF.setEnabled(false);
                startBtn.setEnabled(true);
                connectBtn.setText("DISCONNECT");
            }
            else {
                connectBtn.performClick();
                logTv.append("Invalid IP address!\n");
            }
        }
    }

    public void clearBtnOnClick(View v){
        logTv.setText("");
    }

    public void magnetoSwClick(View v){
        if(magSw.isChecked()){
            logTv.append("\nAppending magnetometer data...\n");
            use_mag.set(true);
            if(magm!=null && magSw.isChecked()) sensorManager.registerListener(magnetometerListener, magm, SensorManager.SENSOR_DELAY_UI);
            else logTv.append("No magnetometer found\n");
        }
        else {
            logTv.append("\nRemoving magnetometer data...\n");
            sensorManager.unregisterListener(magnetometerListener);
            use_mag.set(false);
        }
    }

    public void angleSwClick(View v){
        if(angSw.isChecked()){
            RAD_TO_DEG = 57.295779513082320876798154814105;
            logTv.append("\nGyroscope data type: degrees per second.\n");
            angSw.setText("DEG");
        }
        else{
            RAD_TO_DEG = 1;
            logTv.append("\nGyroscope data type: radians per second.\n");
            angSw.setText("RAD");
        }
    }

    // Reading sensor data via sensorEventListener: Accelerometer
    private class AccelerometerListener implements SensorEventListener {

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            float[] temp = sensorEvent.values.clone();
            for(int i=0; i<3 ;i++) {
                acc_data.remove(i);
                acc_data.add(i,temp[i]);
            }
//            if(acc_data.size()!=3){
//                logTv.append("PROBLEM: ACCL\n");
//            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    }

    // Reading sensor data via sensorEventListener: Gyroscope
    private class GyroscopeListener implements SensorEventListener{

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {

            float[] temp = sensorEvent.values.clone();
            for(int i=0; i<3 ;i++) {
                gyro_data.remove(i);
                gyro_data.add(i,(float) (temp[i] * RAD_TO_DEG));
            }
//            if(gyro_data.size()!=3) {
//                logTv.append("PROBLEM: Gyro\n");
//            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    }

    // Reading sensor data via sensorEventListener: Magnetometer
    private class MagnetometerListener implements SensorEventListener{

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            float[] temp = sensorEvent.values.clone();
            for(int i=0; i<3 ;i++) {
                magm_data.remove(i);
                magm_data.add(i,temp[i]);
            }
//            if(magm_data.size()!=3){
//                logTv.append("PROBLEM: MAGM\n");
//            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    }

    // Runnable which formats the sensor data
    static class FormattingRunnable implements Runnable{

        private WifiTransmissionLoop transmitterLoop;

        FormattingRunnable(){
            transmitterLoop = new WifiTransmissionLoop();
            SystemClock.sleep(100);
        }

        @Override
        public void run() {
            while(loopIsOn){
                StringBuilder msg = new StringBuilder();
                synchronized (acc_data){
                    for (Object acc_datum : acc_data) {
                        msg.append(acc_datum).append(",");
                    }
                }
                synchronized (gyro_data){
                    msg.append(gyro_data.get(0));
                    for(int i=1;i<3;i++){
                        msg.append(",").append(gyro_data.get(i));
                    }
                }
                if(use_mag.get()){
                    synchronized (magm_data){
                        for (Object magm_datum : magm_data) {
                            msg.append(",").append(magm_datum);
                        }
                    }
                }
                transmitterLoop.execute(new TransmittingRunnable(receiverIP, msg.toString()));
                SystemClock.sleep(80);
            }
            transmitterLoop.quit();
        }
    }

    // Runnable which transmits the formatted data
    static class TransmittingRunnable implements Runnable{
        private String ipAddress;
        private String message;

        TransmittingRunnable(String ip, String msg){
            this.ipAddress = ip;
            this.message = msg;
        }

        @Override
        public void run() {
            try {
                Socket socket = new Socket(ipAddress, TALKING_PORT);
                PrintWriter writer = new PrintWriter(socket.getOutputStream());
                writer.write(message);
                writer.flush();
                writer.close();
                socket.close();
            }
            catch (IOException e) {
                Log.e("MessageSender",e.toString());
            }
        }
    }

}
