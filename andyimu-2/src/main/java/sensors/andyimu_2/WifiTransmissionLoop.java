package sensors.andyimu_2;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * This handler class creates an infinite loop (Looper) into which the runnable - TransmittingRunnable
 * is posted multiple times by the FormattingRunnable after data is formatted. Many runnables are
 * added to the MessageQueue as the FormattingRunnable runs continuously. The looper of this is
 * stopped by FormattingRunnable when it is stopped. A total of 2 instances of this class are used -
 * one takes care of transmitting the sensor data while other pings the server.
 */
class WifiTransmissionLoop extends HandlerThread {

    private Handler handler;

    WifiTransmissionLoop(){
        super("WifiTransmissionLoop");
        start();                            // Starts the looper
        handler = new Handler(getLooper());
    }

    // Visible to the instance of class to post tasks onto this loop.
    void execute(Runnable task){
        handler.post(task);
    }

}